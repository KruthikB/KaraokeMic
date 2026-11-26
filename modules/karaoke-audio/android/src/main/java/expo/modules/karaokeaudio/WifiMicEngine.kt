package expo.modules.karaokeaudio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import expo.modules.karaokeaudio.dsp.Level
import expo.modules.karaokeaudio.dsp.VocalProcessor
import expo.modules.karaokeaudio.policy.OutputRouteClassifier
import expo.modules.karaokeaudio.wifi.JitterBuffer
import expo.modules.karaokeaudio.wifi.WifiMicPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

internal data class WifiSnapshot(
  val role: String = "",
  val connected: Boolean = false,
  val advertising: Boolean = false,
  val localAddress: String = "",
  val localPort: Int = 0,
  val remoteAddress: String = "",
  val latencyMs: Double = 0.0,
  val jitterMs: Double = 0.0,
  val bufferMs: Double = 0.0,
  val packetsLost: Long = 0,
  val packetsReceived: Long = 0,
  val audioBackend: String = "",
) {
  fun toMap(): Map<String, Any> = mapOf(
    "role" to role,
    "connected" to connected,
    "advertising" to advertising,
    "localAddress" to localAddress,
    "localPort" to localPort,
    "remoteAddress" to remoteAddress,
    "latencyMs" to latencyMs,
    "jitterMs" to jitterMs,
    "bufferMs" to bufferMs,
    "packetsLost" to packetsLost.toDouble(),
    "packetsReceived" to packetsReceived.toDouble(),
    "audioBackend" to audioBackend,
  )
}

/**
 * Two-phone mic. The sender captures with Oboe (AudioRecord if Oboe will not
 * open), runs the vocal chain, and sends 5 ms PCM frames. The receiver plays
 * them from a 15 ms jitter buffer through an Oboe output stream and does not
 * take the microphone or audio focus, so a music app can keep playing.
 */
internal class WifiMicEngine(
  context: Context,
  private val role: Role,
  private val remoteHost: String,
  private val remotePort: Int,
  private val onLevel: (Float, Float) -> Unit,
  private val onRoute: (String, String) -> Unit,
  private val onError: (String) -> Unit,
) : VocalEngine {
  enum class Role { SENDER, RECEIVER }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val routes = OutputRouteWatch(context) { kind, name ->
    if (role == Role.RECEIVER) {
      routeKind = kind
      routeName = name
      onRoute(kind, name)
    }
  }

  override var gain: Float = 0.85f
  override var reverbMix: Float = 0.06f
  override var echoMix: Float = 0f
  override var gateEnabled: Boolean = true
  override var muted: Boolean = false
  @Volatile override var routeKind: String = OutputRouteClassifier.UNKNOWN
  @Volatile override var routeName: String = ""
  @Volatile override var sampleRate: Int = 0
  @Volatile override var lowLatency: Boolean = false
  @Volatile override var audioBackend: String = ""

  private var outputLatencyMs = 0.0
  @Volatile private var linkLatencyMs = 0.0
  @Volatile private var linkJitterMs = 0.0
  @Volatile private var linkBufferMs = 0.0
  @Volatile private var sentPackets = 0L
  @Volatile private var sawAck = false
  @Volatile private var sawAudio = false
  @Volatile private var lastPacketNs = 0L
  @Volatile private var runningFlag = false

  private var processor = VocalProcessor(WifiMicPacket.DEFAULT_SAMPLE_RATE)
  private var spaceName = "clean"
  private var jitter = JitterBuffer(WifiMicPacket.DEFAULT_SAMPLE_RATE)
  private var socket: DatagramSocket? = null
  private var recorder: AudioRecord? = null
  private var track: AudioTrack? = null
  private var usingOboe = false
  private var frameSamples = WifiMicPacket.DEFAULT_FRAME_SAMPLES
  private var worker: Thread? = null
  private var sideThread: Thread? = null
  private var lastLevelNs = 0L

  override val isRunning: Boolean
    get() = runningFlag

  override val latencyMs: Double
    get() = if (role == Role.SENDER) linkLatencyMs else linkBufferMs + outputLatencyMs

  override fun setSpace(name: String) {
    spaceName = name
    processor.setSpace(name)
  }

  override fun pushSettings() {
    processor.gain = gain
    processor.reverbMix = reverbMix
    processor.echoMix = echoMix
    processor.gateEnabled = gateEnabled
    processor.muted = muted
  }

  override fun start() {
    if (runningFlag) {
      pushSettings()
      return
    }
    MediaMix.prepare(audioManager)
    when (role) {
      Role.SENDER -> startSender()
      Role.RECEIVER -> startReceiver()
    }
  }

  override fun stop() {
    runningFlag = false
    try {
      socket?.close()
    } catch (_: Exception) {
      // Closing unblocks the receive thread.
    }
    socket = null
    if (usingOboe) {
      NativeOboe.stop()
    }
    usingOboe = false
    releaseRecorder()
    releaseTrack()
    join(worker)
    join(sideThread)
    worker = null
    sideThread = null
    routes.stop()
    if (role == Role.RECEIVER) {
      WifiLan.stopAdvertising()
    }
    lowLatency = false
  }

  fun snapshot(): WifiSnapshot {
    val now = System.nanoTime()
    val fresh = lastPacketNs != 0L && now - lastPacketNs < 1_000_000_000L
    val connected = fresh && if (role == Role.SENDER) sawAck else sawAudio
    val bufferMs = if (role == Role.RECEIVER) jitter.depthMs() else linkBufferMs
    if (role == Role.RECEIVER) {
      linkBufferMs = bufferMs
      linkJitterMs = jitter.jitterMs()
    }
    return WifiSnapshot(
      role = if (role == Role.SENDER) "sender" else "receiver",
      connected = connected,
      advertising = role == Role.RECEIVER && WifiLan.isAdvertising(),
      localAddress = WifiLan.ipv4(),
      localPort = if (role == Role.RECEIVER) WifiMicPacket.DEFAULT_PORT else socket?.localPort ?: 0,
      remoteAddress = if (role == Role.SENDER) remoteHost else "",
      latencyMs = latencyMs,
      jitterMs = linkJitterMs,
      bufferMs = bufferMs,
      packetsLost = if (role == Role.RECEIVER) jitter.packetsLost() else 0,
      packetsReceived = if (role == Role.RECEIVER) jitter.packetsReceived() else sentPackets,
      audioBackend = audioBackend,
    )
  }

  private fun startSender() {
    val address = try {
      InetAddress.getByName(remoteHost)
    } catch (_: Exception) {
      throw IllegalArgumentException("That IP address could not be reached.")
    }
    val opened = openInput()
    sampleRate = opened
    frameSamples = (opened / 200).coerceIn(96, WifiMicPacket.MAX_FRAMES)
    processor = VocalProcessor(opened)
    processor.setSpace(spaceName)
    pushSettings()
    val port = if (remotePort in 1..65535) remotePort else WifiMicPacket.DEFAULT_PORT
    socket = DatagramSocket().also { tuneSocket(it) }
    runningFlag = true
    routeKind = "wifi"
    routeName = remoteHost
    mainHandler.post { onRoute("wifi", remoteHost) }
    val capture = Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
      sendLoop(address, port)
    }, "stagemic-wifi-send")
    val acks = Thread({ ackLoop() }, "stagemic-wifi-ack")
    worker = capture
    sideThread = acks
    capture.start()
    acks.start()
  }

  private fun startReceiver() {
    val bound = try {
      DatagramSocket(null).also { created ->
        created.reuseAddress = true
        created.bind(InetSocketAddress(WifiMicPacket.DEFAULT_PORT))
        tuneSocket(created)
      }
    } catch (_: Exception) {
      throw IllegalStateException("Port ${WifiMicPacket.DEFAULT_PORT} is already in use on this phone.")
    }
    socket = bound
    WifiLan.startAdvertising(appContext, WifiMicPacket.DEFAULT_PORT)
    routes.start()
    runningFlag = true
    val network = Thread({ receiveLoop() }, "stagemic-wifi-recv")
    worker = network
    network.start()
  }

  private fun sendLoop(address: InetAddress, port: Int) {
    val floats = FloatArray(frameSamples)
    val processed = FloatArray(frameSamples)
    val pcm = ShortArray(frameSamples)
    val packetBytes = ByteArray(WifiMicPacket.encodedAudioSize(frameSamples))
    val datagram = DatagramPacket(packetBytes, packetBytes.size)
    var sequence = 0L
    while (runningFlag) {
      val count = readCapture(floats)
      if (count < 0) {
        break
      }
      if (count == 0) {
        continue
      }
      pushSettings()
      processor.process(floats, processed, count)
      emitLevel(processed, count)
      val usable = count.coerceAtMost(pcm.size)
      for (index in 0 until usable) {
        pcm[index] = (processed[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
      }
      val size = WifiMicPacket.encodeAudioInto(
        WifiMicPacket.Audio(sequence, monotonicUs(), sampleRate, pcm.copyOf(usable)),
        packetBytes,
      )
      if (size <= 0) {
        continue
      }
      datagram.length = size
      datagram.address = address
      datagram.port = port
      try {
        socket?.send(datagram)
        sentPackets += 1
        lastPacketNs = System.nanoTime()
        sequence += 1
      } catch (_: Exception) {
        if (!runningFlag) {
          break
        }
      }
    }
    if (runningFlag) {
      fail("The handheld mic stopped sending. Start it again.")
    }
  }

  private fun ackLoop() {
    val buffer = ByteArray(64)
    val datagram = DatagramPacket(buffer, buffer.size)
    while (runningFlag) {
      try {
        socket?.receive(datagram) ?: break
      } catch (_: SocketTimeoutException) {
        continue
      } catch (_: Exception) {
        break
      }
      val ack = WifiMicPacket.decode(datagram.data, datagram.length) as? WifiMicPacket.Ack ?: continue
      val rttUs = WifiMicPacket.wrappedDeltaUs(ack.timestampUs, monotonicUs())
      if (rttUs > 2_000_000L) {
        continue
      }
      linkLatencyMs = rttUs / 2000.0 + ack.bufferDepthMs
      linkJitterMs = ack.jitterMs
      linkBufferMs = ack.bufferDepthMs
      sawAck = true
      lastPacketNs = System.nanoTime()
    }
  }

  private fun receiveLoop() {
    val buffer = ByteArray(WifiMicPacket.AUDIO_HEADER + WifiMicPacket.MAX_FRAMES * 2)
    val datagram = DatagramPacket(buffer, buffer.size)
    var playout: Thread? = null
    while (runningFlag) {
      try {
        socket?.receive(datagram) ?: break
      } catch (_: SocketTimeoutException) {
        continue
      } catch (_: Exception) {
        break
      }
      val audio = WifiMicPacket.decode(datagram.data, datagram.length) as? WifiMicPacket.Audio ?: continue
      if (sampleRate == 0) {
        try {
          openOutput(audio.sampleRate)
        } catch (error: Exception) {
          fail(error.message ?: "The speaker output did not open.")
          break
        }
        jitter = JitterBuffer(audio.sampleRate)
        val thread = Thread({
          Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
          playLoop()
        }, "stagemic-wifi-play")
        sideThread = thread
        playout = thread
        thread.start()
      }
      if (audio.sampleRate != sampleRate) {
        continue
      }
      jitter.push(audio.sequence, audio.samples, System.nanoTime())
      sawAudio = true
      lastPacketNs = System.nanoTime()
      sendAck(datagram, audio)
    }
    runningFlag = false
    playout?.join(500)
  }

  private fun sendAck(from: DatagramPacket, audio: WifiMicPacket.Audio) {
    val ack = WifiMicPacket.encodeAck(
      WifiMicPacket.Ack(
        sequence = audio.sequence,
        timestampUs = audio.timestampUs,
        bufferDepthMs = jitter.depthMs(),
        jitterMs = jitter.jitterMs(),
      ),
    )
    val reply = DatagramPacket(ack, ack.size, from.address, from.port)
    try {
      socket?.send(reply)
    } catch (_: Exception) {
      // The sender learns the link from the next ack.
    }
  }

  private fun playLoop() {
    val frames = frameSamples.coerceAtLeast(96)
    val pcm = ShortArray(frames)
    val stereo = ShortArray(frames * 2)
    while (runningFlag) {
      jitter.pull(pcm, frames)
      linkBufferMs = jitter.depthMs()
      linkJitterMs = jitter.jitterMs()
      if (muted) {
        java.util.Arrays.fill(pcm, 0)
      }
      emitLevelFromShorts(pcm, frames)
      val wrote = if (usingOboe) {
        NativeOboe.write(pcm, frames)
      } else {
        var offset = 0
        for (index in 0 until frames) {
          stereo[offset] = pcm[index]
          stereo[offset + 1] = pcm[index]
          offset += 2
        }
        track?.write(stereo, 0, frames * 2, AudioTrack.WRITE_BLOCKING) ?: -1
      }
      if (wrote < 0) {
        break
      }
    }
    if (runningFlag) {
      fail("The speaker output stopped. Start the receiver again.")
    }
  }

  private fun openInput(): Int {
    val burst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240
    if (NativeOboe.available && NativeOboe.startInput(WifiMicPacket.DEFAULT_SAMPLE_RATE, burst, routes.micId())) {
      usingOboe = true
      audioBackend = NativeOboe.backend()
      lowLatency = NativeOboe.lowLatency()
      outputLatencyMs = 0.0
      return NativeOboe.sampleRate().takeIf { it > 0 } ?: WifiMicPacket.DEFAULT_SAMPLE_RATE
    }
    NativeOboe.stop()
    usingOboe = false
    val capture = buildRecorder(WifiMicPacket.DEFAULT_SAMPLE_RATE, burst)
    capture.startRecording()
    recorder = capture
    audioBackend = AudioBackend.AUDIOTRACK_FALLBACK
    lowLatency = false
    return capture.sampleRate.takeIf { it > 0 } ?: WifiMicPacket.DEFAULT_SAMPLE_RATE
  }

  private fun openOutput(rate: Int) {
    val burst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240
    frameSamples = (rate / 200).coerceIn(96, WifiMicPacket.MAX_FRAMES)
    sampleRate = rate
    if (NativeOboe.available && NativeOboe.startOutput(rate, burst, routes.outputId())) {
      usingOboe = true
      audioBackend = NativeOboe.backend()
      lowLatency = NativeOboe.lowLatency()
      outputLatencyMs = NativeOboe.latencyMs()
      return
    }
    NativeOboe.stop()
    usingOboe = false
    val playback = buildTrack(rate, burst)
    playback.play()
    track = playback
    audioBackend = AudioBackend.AUDIOTRACK_FALLBACK
    lowLatency = false
    outputLatencyMs = AudioLatency.milliseconds(playback.bufferSizeInFrames, rate)
  }

  private fun readCapture(floats: FloatArray): Int {
    if (usingOboe) {
      return NativeOboe.read(floats, floats.size)
    }
    val capture = recorder ?: return -1
    val shorts = ShortArray(floats.size)
    val count = capture.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
    if (count <= 0) {
      return count
    }
    for (index in 0 until count) {
      floats[index] = shorts[index] / 32768f
    }
    return count
  }

  @SuppressLint("MissingPermission")
  private fun buildRecorder(rate: Int, frames: Int): AudioRecord {
    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) {
      throw IllegalStateException("This phone cannot open the microphone.")
    }
    val bytes = maxOf(minBuffer, frames * 2 * 2)
    val sources = ArrayList<Int>(3)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      sources.add(MediaRecorder.AudioSource.UNPROCESSED)
    }
    sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
    sources.add(MediaRecorder.AudioSource.MIC)
    for (source in sources) {
      try {
        val capture = AudioRecord.Builder()
          .setAudioSource(source)
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(rate)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .build(),
          )
          .setBufferSizeInBytes(bytes)
          .build()
        if (capture.state == AudioRecord.STATE_INITIALIZED) {
          return capture
        }
        capture.release()
      } catch (_: Exception) {
        // Try the next source. Never VOICE_COMMUNICATION: that is call audio.
      }
    }
    throw IllegalStateException("Could not open the microphone.")
  }

  private fun buildTrack(rate: Int, frames: Int): AudioTrack {
    val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) {
      throw IllegalStateException("This phone cannot open the speaker output.")
    }
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()
    val format = AudioFormat.Builder()
      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .setSampleRate(rate)
      .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
      .build()
    val playback = AudioTrack.Builder()
      .setAudioAttributes(attributes)
      .setAudioFormat(format)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(maxOf(minBuffer, frames * 2 * 2))
      .build()
    if (playback.state != AudioTrack.STATE_INITIALIZED) {
      playback.release()
      throw IllegalStateException("The speaker output did not initialize.")
    }
    return playback
  }

  private fun tuneSocket(datagram: DatagramSocket) {
    datagram.soTimeout = 200
    try {
      datagram.sendBufferSize = 64 * 1024
      datagram.receiveBufferSize = 64 * 1024
      datagram.trafficClass = 0x10
    } catch (_: Exception) {
      // Buffer sizes are a hint.
    }
  }

  private fun emitLevel(samples: FloatArray, count: Int) {
    val now = System.nanoTime()
    if (now - lastLevelNs < 66_000_000L) {
      return
    }
    lastLevelNs = now
    val (rms, peak) = Level.rmsAndPeak(samples, count)
    mainHandler.post { onLevel(rms, peak) }
  }

  private fun emitLevelFromShorts(samples: ShortArray, count: Int) {
    val now = System.nanoTime()
    if (now - lastLevelNs < 66_000_000L) {
      return
    }
    lastLevelNs = now
    var sum = 0.0
    var peak = 0f
    val frames = count.coerceAtMost(samples.size)
    for (index in 0 until frames) {
      val magnitude = kotlin.math.abs(samples[index] / 32768f)
      sum += magnitude * magnitude
      if (magnitude > peak) {
        peak = magnitude
      }
    }
    val rms = kotlin.math.sqrt(sum / frames.coerceAtLeast(1)).toFloat()
    mainHandler.post { onLevel(rms, peak) }
  }

  private fun fail(message: String) {
    runningFlag = false
    mainHandler.post { onError(message) }
  }

  private fun releaseRecorder() {
    recorder?.let { capture ->
      try {
        if (capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          capture.stop()
        }
      } catch (_: IllegalStateException) {
        // Already stopped.
      }
      capture.release()
    }
    recorder = null
  }

  private fun releaseTrack() {
    track?.let { playback ->
      try {
        playback.pause()
        playback.flush()
        playback.stop()
      } catch (_: IllegalStateException) {
        // Already stopped.
      }
      playback.release()
    }
    track = null
  }

  private fun join(thread: Thread?) {
    try {
      thread?.join(700)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun monotonicUs(): Long = (System.nanoTime() / 1_000L) and 0xFFFF_FFFFL
}
