package expo.modules.karaokeaudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class KaraokeAudioModule : Module() {
  private val events = object : KaraokeEvents {
    override fun onLevel(rms: Double, peak: Double) {
      send("onLevel", mapOf("rms" to rms, "peak" to peak))
    }

    override fun onRoute(kind: String, name: String) {
      send("onRoute", mapOf("kind" to kind, "name" to name))
    }

    override fun onState(running: Boolean, muted: Boolean) {
      send("onState", mapOf("running" to running, "muted" to muted))
    }

    override fun onError(message: String) {
      send("onError", mapOf("message" to message))
    }
  }

  override fun definition() = ModuleDefinition {
    Name("KaraokeAudio")

    Events("onLevel", "onRoute", "onState", "onError")

    OnCreate {
      KaraokeRuntime.events = events
      appContext.reactContext?.let { KaraokeRuntime.watchRoutes(it) }
    }

    OnDestroy {
      appContext.reactContext?.let { KaraokeRuntime.stopWatchingRoutes(it) }
      if (KaraokeRuntime.events === events) {
        KaraokeRuntime.events = null
      }
    }

    AsyncFunction("requestPermission") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
      ) == PackageManager.PERMISSION_GRANTED
    }

    Function("getPermission") {
      val context = appContext.reactContext ?: return@Function "undetermined"
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
      ) == PackageManager.PERMISSION_GRANTED
      if (granted) "granted" else "undetermined"
    }

    Function("getStatus") {
      appContext.reactContext?.let { KaraokeRuntime.watchRoutes(it) }
      mapOf(
        "running" to KaraokeRuntime.isRunning(),
        "muted" to KaraokeRuntime.muted,
        "route" to KaraokeRuntime.routeKind,
        "routeName" to KaraokeRuntime.routeName,
        "sampleRate" to KaraokeRuntime.sampleRate(),
        "lowLatency" to KaraokeRuntime.lowLatency(),
      )
    }

    AsyncFunction("start") { options: Map<String, Any?> ->
      val context = appContext.reactContext
        ?: throw IllegalStateException("StageMic is not ready yet.")
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) {
        throw SecurityException("Microphone permission is not granted.")
      }
      KaraokeRuntime.events = events
      KaraokeRuntime.applyOptions(options)
      KaraokeRuntime.startEngine(context.applicationContext)
      ContextCompat.startForegroundService(
        context,
        Intent(context, KaraokeForegroundService::class.java),
      )
    }

    AsyncFunction("stop") {
      val context = appContext.reactContext
      KaraokeRuntime.stopEngine()
      context?.stopService(Intent(context, KaraokeForegroundService::class.java))
    }

    Function("setMuted") { muted: Boolean ->
      KaraokeRuntime.setMuted(muted)
    }

    Function("setGain") { value: Double ->
      KaraokeRuntime.setGain(value.toFloat())
    }

    Function("setReverb") { value: Double ->
      KaraokeRuntime.setReverb(value.toFloat())
    }

    Function("setEcho") { value: Double ->
      KaraokeRuntime.setEcho(value.toFloat())
    }

    Function("setGate") { enabled: Boolean ->
      KaraokeRuntime.setGate(enabled)
    }

    Function("setPreset") { name: String ->
      KaraokeRuntime.setPreset(name)
    }
  }

  private fun send(name: String, body: Map<String, Any>) {
    try {
      sendEvent(name, body)
    } catch (_: Throwable) {
      // The JS runtime can be paused while the service keeps running.
    }
  }
}
