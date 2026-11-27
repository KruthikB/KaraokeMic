package expo.modules.karaokeaudio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Mic to speaker on this phone through Oboe. Falls back to [MicPassthroughEngine]
 * when the native stream refuses to open. Does not request audio focus.
 */
internal class OboeDirectEngine(
  context: Context,
  private val onLevel: (Float, Float) -> Unit,
  private val onRoute: (String, String) -> Unit,
) : VocalEngine {
  private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val routes = OutputRouteWatch(context) { kind, name ->
    routeKind = kind
    routeName = name
    onRoute(kind, name)
  }
  private var started = false

  override var gain: Float = 0.85f
  override var reverbMix: Float = 0.06f
  override var echoMix: Float = 0f
  override var gateEnabled: Boolean = true
  override var muted: Boolean = false
  override var routeKind: String = "unknown"
  override var routeName: String = ""

  override val isRunning: Boolean
    get() = started && NativeOboe.isRunning()

  override val sampleRate: Int
    get() = if (started) NativeOboe.sampleRate() else 0

  override val lowLatency: Boolean
    get() = started && NativeOboe.lowLatency()

  override val audioBackend: String
    get() = if (started) NativeOboe.backend() else ""

  override val latencyMs: Double
    get() = if (started) NativeOboe.latencyMs() else 0.0

  private var spaceName: String = "clean"
  private val levelPoll = object : Runnable {
    override fun run() {
      if (!isRunning) {
        return
      }
      onLevel(NativeOboe.rms(), NativeOboe.peak())
      mainHandler.postDelayed(this, 66)
    }
  }

  override fun setSpace(name: String) {
    spaceName = name
    NativeOboe.setSpace(name)
  }

  override fun pushSettings() {
    NativeOboe.setGain(gain)
    NativeOboe.setReverb(reverbMix)
    NativeOboe.setEcho(echoMix)
    NativeOboe.setGate(gateEnabled)
    NativeOboe.setMuted(muted)
    NativeOboe.setSpace(spaceName)
  }

  override fun start() {
    if (started && NativeOboe.isRunning()) {
      pushSettings()
      return
    }
    if (!NativeOboe.available) {
      throw IllegalStateException("Oboe was not packaged in this build.")
    }
    MediaMix.prepare(audioManager)
    val rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
    val burst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
    pushSettings()
    val opened = NativeOboe.startDuplex(
      sampleRate = rate,
      framesPerBurst = burst.coerceIn(96, 512),
      inputDeviceId = routes.micId(),
      outputDeviceId = routes.outputId(),
    )
    if (!opened) {
      NativeOboe.stop()
      val detail = NativeOboe.lastError().ifBlank { "Oboe did not open." }
      throw IllegalStateException(detail)
    }
    started = true
    routes.start()
    mainHandler.post(levelPoll)
  }

  override fun stop() {
    started = false
    mainHandler.removeCallbacks(levelPoll)
    routes.stop()
    NativeOboe.stop()
  }
}
