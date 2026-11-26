package expo.modules.karaokeaudio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import expo.modules.karaokeaudio.dsp.Level
import expo.modules.karaokeaudio.dsp.VocalProcessor
import expo.modules.karaokeaudio.policy.OutputRouteClassifier

/**
 * Reads the phone microphone and writes it to the current media output.
 *
 * The track uses USAGE_MEDIA, so it mixes with Spotify and stays on stereo
 * A2DP or a cable. It does not request audio focus and it does not start
 * Bluetooth SCO, which would force the low-quality call profile.
 */
class MicPassthroughEngine(
  context: Context,
  private val onLevel: (Float, Float) -> Unit,
  private val onRoute: (String, String) -> Unit,
  private val onError: (String) -> Unit,
) : VocalEngine {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile override var gain: Float = 0.85f
  @Volatile override var reverbMix: Float = 0.06f
  @Volatile override var echoMix: Float = 0f
  @Volatile override var gateEnabled: Boolean = true
  @Volatile override var muted: Boolean = false

  @Volatile override var sampleRate: Int = 48_000
    private set

  @Volatile override var lowLatency: Boolean = false
    private set

  @Volatile override var audioBackend: String = AudioBackend.AUDIOTRACK_FALLBACK
    private set

  @Volatile override var latencyMs: Double = 0.0
    private set

  @Volatile override var routeKind: String = OutputRouteClassifier.UNKNOWN
    private set

  @Volatile override var routeName: String = ""
    private set

  private var processor = VocalProcessor(48_000)
  private var spaceName: String = "clean"
  private var recorder: AudioRecord? = null
  private var track: AudioTrack? = null
  private var thread: Thread? = null

  @Volatile private var running = false

  private var lastLevelNs = 0L
  private var deviceCallback: AudioDeviceCallback? = null

  override val isRunning: Boolean
    get() = running

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

  @SuppressLint("MissingPermission")
  override fun start() {
    if (running) {
      pushSettings()
      return
    }
    prepareMixPolicy()
    val rate = chooseSampleRate()
    sampleRate = rate
    processor = VocalProcessor(rate)
    processor.setSpace(spaceName)
    pushSettings()
    val frames = burstFrames()
    val playback = buildTrack(rate, frames)
    val capture = buildRecorder(rate, frames)
    preferPhoneMic(capture)
    preferMediaOutput(playback)
    track = playback
    recorder = capture
    capture.startRecording()
    playback.play()
    lowLatency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      playback.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
    } else {
      false
    }
    audioBackend = AudioBackend.AUDIOTRACK_FALLBACK
    latencyMs = AudioLatency.milliseconds(capture.bufferSizeInFrames + playback.bufferSizeInFrames, rate)
    running = true
    publishRoute(playback.routedDevice)
    watchRoute(playback)
    val worker = Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
      loop(capture, playback, frames)
    }, "stagemic-mic")
    thread = worker
    worker.start()
  }

  override fun stop() {
    running = false
    val worker = thread
    thread = null
    try {
      worker?.join(700)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    deviceCallback?.let { callback ->
      audioManager.unregisterAudioDeviceCallback(callback)
    }
    deviceCallback = null
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
    lowLatency = false
    latencyMs = 0.0
  }

  private fun loop(capture: AudioRecord, playback: AudioTrack, frames: Int) {
    val readBuffer = ShortArray(frames)
    val floatBuffer = FloatArray(frames)
    val processed = FloatArray(frames)
    val stereo = ShortArray(frames * 2)
    while (running) {
      val count = capture.read(readBuffer, 0, frames, AudioRecord.READ_BLOCKING)
      if (count == 0) {
        continue
      }
      if (count < 0) {
        fail("The microphone stopped. Open StageMic and start it again.")
        break
      }
      for (index in 0 until count) {
        floatBuffer[index] = readBuffer[index] / 32768f
      }
      val (rms, peak) = Level.rmsAndPeak(floatBuffer, count)
      emitLevel(rms, peak)
      pushSettings()
      processor.process(floatBuffer, processed, count)
      var offset = 0
      for (index in 0 until count) {
        val sample = (processed[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        stereo[offset] = sample
        stereo[offset + 1] = sample
        offset += 2
      }
      val written = playback.write(stereo, 0, count * 2, AudioTrack.WRITE_BLOCKING)
      if (written < 0) {
        fail("The speaker output stopped. Reconnect it and start the mic again.")
        break
      }
    }
  }

  private fun prepareMixPolicy() {
    MediaMix.prepare(audioManager)
  }

  private fun chooseSampleRate(): Int {
    val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    val candidates = listOfNotNull(nativeRate, 48_000, 44_100).distinct()
    for (candidate in candidates) {
      val minBuffer = AudioRecord.getMinBufferSize(
        candidate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
      if (minBuffer > 0) {
        return candidate
      }
    }
    throw IllegalStateException("This phone has no supported microphone sample rate.")
  }

  private fun burstFrames(): Int {
    val reported = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
    return (reported ?: 256).coerceIn(96, 512)
  }

  private fun buildTrack(rate: Int, frames: Int): AudioTrack {
    val minBuffer = AudioTrack.getMinBufferSize(
      rate,
      AudioFormat.CHANNEL_OUT_STEREO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) {
      throw IllegalStateException("This phone cannot open the speaker output.")
    }
    val lowLatencyBytes = maxOf(minBuffer, frames * 2 * 2)
    try {
      return createTrack(rate, lowLatencyBytes, lowLatency = true)
    } catch (_: IllegalArgumentException) {
      return createTrack(rate, minBuffer * 2, lowLatency = false)
    }
  }

  private fun createTrack(rate: Int, bufferBytes: Int, lowLatency: Boolean): AudioTrack {
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()
    val format = AudioFormat.Builder()
      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .setSampleRate(rate)
      .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
      .build()
    val builder = AudioTrack.Builder()
      .setAudioAttributes(attributes)
      .setAudioFormat(format)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(bufferBytes.coerceAtLeast(1))
    if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    }
    val playback = builder.build()
    if (playback.state != AudioTrack.STATE_INITIALIZED) {
      playback.release()
      throw IllegalStateException("The speaker output did not initialize.")
    }
    return playback
  }

  @SuppressLint("MissingPermission")
  private fun buildRecorder(rate: Int, frames: Int): AudioRecord {
    val minBuffer = AudioRecord.getMinBufferSize(
      rate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
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
    var lastError: Exception? = null
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
      } catch (error: Exception) {
        lastError = error
      }
    }
    throw IllegalStateException("Could not open the microphone.", lastError)
  }

  private fun preferPhoneMic(capture: AudioRecord) {
    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val phoneMic = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: return
    capture.setPreferredDevice(phoneMic)
  }

  private fun preferMediaOutput(playback: AudioTrack) {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val chosen = OutputRouteClassifier.prefer(outputs.map { it.type }) ?: return
    val device = outputs.firstOrNull { it.type == chosen } ?: return
    playback.setPreferredDevice(device)
  }

  private fun watchRoute(playback: AudioTrack) {
    playback.addOnRoutingChangedListener(
      AudioRouting.OnRoutingChangedListener { router ->
        publishRoute(router.routedDevice)
        val device = router.routedDevice ?: return@OnRoutingChangedListener
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
          preferMediaOutput(playback)
        }
      },
      mainHandler,
    )
    val callback = object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        preferMediaOutput(playback)
        publishRoute(playback.routedDevice)
      }

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        preferMediaOutput(playback)
        publishRoute(playback.routedDevice)
      }
    }
    deviceCallback = callback
    audioManager.registerAudioDeviceCallback(callback, mainHandler)
  }

  private fun publishRoute(device: AudioDeviceInfo?) {
    if (device == null) {
      routeKind = OutputRouteClassifier.UNKNOWN
      routeName = ""
      mainHandler.post { onRoute(routeKind, routeName) }
      return
    }
    routeKind = OutputRouteClassifier.classify(device.type)
    routeName = try {
      device.productName?.toString().orEmpty()
    } catch (_: SecurityException) {
      ""
    }
    mainHandler.post { onRoute(routeKind, routeName) }
  }

  private fun emitLevel(rms: Float, peak: Float) {
    val now = System.nanoTime()
    if (now - lastLevelNs < 66_000_000L) {
      return
    }
    lastLevelNs = now
    mainHandler.post { onLevel(rms, peak) }
  }

  private fun fail(message: String) {
    running = false
    mainHandler.post { onError(message) }
  }
}
