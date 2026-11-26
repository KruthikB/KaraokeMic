package expo.modules.karaokeaudio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import expo.modules.karaokeaudio.policy.OutputRouteClassifier

interface KaraokeEvents {
  fun onLevel(rms: Double, peak: Double)
  fun onRoute(kind: String, name: String)
  fun onState(running: Boolean, muted: Boolean)
  fun onError(message: String)
}

/**
 * Shared by the Expo module and the foreground service. The service keeps
 * the process alive after the screen is locked; this object keeps the engine.
 */
object KaraokeRuntime {
  @Volatile var events: KaraokeEvents? = null
  @Volatile var onMuteChanged: ((Boolean) -> Unit)? = null
  @Volatile var onRequestStop: (() -> Unit)? = null

  // JVM names stay off setGain/setReverb/… so those functions can keep the
  // names the Expo module calls. Kotlin still uses the property names.
  @get:JvmName("gainLevel")
  @set:JvmName("gainLevel")
  @Volatile var gain: Float = 0.85f
  @get:JvmName("reverbLevel")
  @set:JvmName("reverbLevel")
  @Volatile var reverb: Float = 0.06f
  @get:JvmName("echoLevel")
  @set:JvmName("echoLevel")
  @Volatile var echo: Float = 0f
  @get:JvmName("gateEnabledFlag")
  @set:JvmName("gateEnabledFlag")
  @Volatile var gate: Boolean = true
  @get:JvmName("presetName")
  @set:JvmName("presetName")
  @Volatile var preset: String = "clean"
  @get:JvmName("mutedFlag")
  @set:JvmName("mutedFlag")
  @Volatile var muted: Boolean = false
  @Volatile var routeKind: String = OutputRouteClassifier.UNKNOWN
  @Volatile var routeName: String = ""
  @Volatile var lastError: String? = null
  @Volatile var mode: String = "direct"
  @Volatile var wifiRole: String = ""
  @Volatile var wifiHost: String = ""
  @Volatile var wifiPort: Int = expo.modules.karaokeaudio.wifi.WifiMicPacket.DEFAULT_PORT

  private val mainHandler = Handler(Looper.getMainLooper())
  private var engine: VocalEngine? = null
  private var routeCallback: AudioDeviceCallback? = null

  fun isRunning(): Boolean = engine?.isRunning == true

  fun capturesMicrophone(): Boolean = !(mode == "wifi" && wifiRole == "receiver")

  /** Banner updates before the mic is on, so a phone speaker warns about howling. */
  fun watchRoutes(context: Context) {
    val appContext = context.applicationContext
    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    synchronized(this) {
      if (routeCallback != null) {
        publishIdleRoute(audioManager)
        return
      }
      val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
          publishIdleRoute(audioManager)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
          publishIdleRoute(audioManager)
        }
      }
      routeCallback = callback
      audioManager.registerAudioDeviceCallback(callback, mainHandler)
    }
    publishIdleRoute(audioManager)
  }

  fun stopWatchingRoutes(context: Context) {
    val callback = synchronized(this) {
      val current = routeCallback
      routeCallback = null
      current
    } ?: return
    val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.unregisterAudioDeviceCallback(callback)
  }

  private fun publishIdleRoute(audioManager: AudioManager) {
    if (isRunning()) {
      return
    }
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    val chosen = OutputRouteClassifier.prefer(outputs.map { it.type })
    if (chosen == null) {
      routeKind = OutputRouteClassifier.UNKNOWN
      routeName = ""
    } else {
      val device = outputs.firstOrNull { it.type == chosen }
      routeKind = OutputRouteClassifier.classify(chosen)
      routeName = deviceLabel(device)
    }
    events?.onRoute(routeKind, routeName)
  }

  private fun deviceLabel(device: AudioDeviceInfo?): String {
    if (device == null) {
      return ""
    }
    return try {
      device.productName?.toString().orEmpty()
    } catch (_: SecurityException) {
      ""
    }
  }

  fun sampleRate(): Int = engine?.sampleRate ?: 0

  fun lowLatency(): Boolean = engine?.lowLatency == true

  fun audioBackend(): String = engine?.audioBackend ?: ""

  fun latencyMs(): Double = engine?.latencyMs ?: 0.0

  fun applyOptions(options: Map<String, Any?>) {
    gain = (options["gain"] as? Number)?.toFloat()?.coerceIn(0f, 1.5f) ?: gain
    reverb = (options["reverb"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: reverb
    echo = (options["echo"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: echo
    gate = options["gate"] as? Boolean ?: gate
    val requested = options["preset"] as? String
    if (requested == "clean" || requested == "studio" || requested == "hall") {
      preset = requested
      engine?.setSpace(requested)
    }
    val requestedMode = options["mode"] as? String
    if (requestedMode == "direct" || requestedMode == "wifi") {
      mode = requestedMode
    }
    val requestedRole = options["wifiRole"] as? String
    if (requestedRole == "receiver" || requestedRole == "sender") {
      wifiRole = requestedRole
    }
    val requestedHost = options["wifiHost"] as? String
    if (requestedHost != null) {
      wifiHost = requestedHost.trim()
    }
    val requestedPort = (options["wifiPort"] as? Number)?.toInt()
    if (requestedPort != null && requestedPort in 1..65535) {
      wifiPort = requestedPort
    }
    pushToEngine()
  }

  fun setGain(value: Float) {
    gain = value.coerceIn(0f, 1.5f)
    pushToEngine()
  }

  fun setReverb(value: Float) {
    reverb = value.coerceIn(0f, 1f)
    pushToEngine()
  }

  fun setEcho(value: Float) {
    echo = value.coerceIn(0f, 1f)
    pushToEngine()
  }

  fun setGate(enabled: Boolean) {
    gate = enabled
    pushToEngine()
  }

  fun setPreset(name: String) {
    if (name == "clean" || name == "studio" || name == "hall") {
      preset = name
      engine?.setSpace(name)
    }
  }

  fun setMuted(value: Boolean) {
    muted = value
    engine?.muted = value
    engine?.pushSettings()
    val running = isRunning()
    mainHandler.post {
      events?.onState(running, value)
      onMuteChanged?.invoke(value)
    }
  }

  fun toggleMute() {
    setMuted(!muted)
  }

  @Synchronized
  fun startEngine(context: Context) {
    val current = engine
    if (current?.isRunning == true && sameSession(current)) {
      current.setSpace(preset)
      pushToEngine()
      return
    }
    current?.stop()
    engine = null
    lastError = null
    if (mode == "wifi" && wifiRole == "sender" && wifiHost.isBlank()) {
      throw IllegalArgumentException("Choose a speaker phone or enter its IP address.")
    }
    val created = try {
      launch(openEngine(context))
    } catch (error: Throwable) {
      if (mode == "wifi") {
        val message = error.message ?: "The Wi-Fi mic did not start."
        lastError = message
        throw IllegalStateException(message, error)
      }
      android.util.Log.w("StageMic", "Oboe did not open, using AudioTrack", error)
      try {
        launch(fallbackEngine(context))
      } catch (fallback: Throwable) {
        val message = fallback.message ?: "The microphone did not start."
        lastError = message
        throw IllegalStateException(message, fallback)
      }
    }
    engine = created
    routeKind = created.routeKind
    routeName = created.routeName
    mainHandler.post {
      events?.onState(true, muted)
      events?.onRoute(created.routeKind, created.routeName)
    }
  }

  private fun sameSession(current: VocalEngine): Boolean {
    if (mode == "wifi") {
      return current is WifiMicEngine
    }
    return current is OboeDirectEngine || current is MicPassthroughEngine
  }

  private fun launch(created: VocalEngine): VocalEngine {
    created.setSpace(preset)
    created.gain = gain
    created.reverbMix = reverb
    created.echoMix = echo
    created.gateEnabled = gate
    created.muted = muted
    created.pushSettings()
    try {
      created.start()
    } catch (error: Throwable) {
      created.stop()
      throw error
    }
    return created
  }

  private fun openEngine(context: Context): VocalEngine {
    if (mode == "wifi") {
      val wifiRole = if (this.wifiRole == "sender") WifiMicEngine.Role.SENDER else WifiMicEngine.Role.RECEIVER
      return WifiMicEngine(
        context = context,
        role = wifiRole,
        remoteHost = wifiHost,
        remotePort = wifiPort,
        onLevel = levelListener(),
        onRoute = routeListener(),
        onError = errorListener(),
      )
    }
    return OboeDirectEngine(
      context = context,
      onLevel = levelListener(),
      onRoute = routeListener(),
    )
  }

  private fun fallbackEngine(context: Context): MicPassthroughEngine {
    return MicPassthroughEngine(
      context = context,
      onLevel = levelListener(),
      onRoute = routeListener(),
      onError = errorListener(),
    )
  }

  private fun levelListener(): (Float, Float) -> Unit {
    return { rms, peak -> events?.onLevel(rms.toDouble(), peak.toDouble()) }
  }

  private fun routeListener(): (String, String) -> Unit {
    return { kind, name ->
      routeKind = kind
      routeName = name
      events?.onRoute(kind, name)
    }
  }

  private fun errorListener(): (String) -> Unit {
    return { message ->
      lastError = message
      stopEngine()
      events?.onError(message)
      mainHandler.post { onRequestStop?.invoke() }
    }
  }

  internal fun wifiSnapshot(): WifiSnapshot {
    val current = engine
    if (current is WifiMicEngine) {
      return current.snapshot()
    }
    return WifiSnapshot(
      role = wifiRole,
      advertising = WifiLan.isAdvertising(),
      localAddress = WifiLan.ipv4(),
      localPort = if (wifiRole == "receiver") wifiPort else 0,
      remoteAddress = wifiHost,
    )
  }

  @Synchronized
  fun stopEngine() {
    val current = engine
    engine = null
    current?.stop()
    muted = false
    mainHandler.post { events?.onState(false, false) }
  }

  private fun pushToEngine() {
    val current = engine ?: return
    current.gain = gain
    current.reverbMix = reverb
    current.echoMix = echo
    current.gateEnabled = gate
    current.muted = muted
    current.pushSettings()
  }
}
