package expo.modules.karaokeaudio

internal interface VocalEngine {
  val isRunning: Boolean
  val sampleRate: Int
  val lowLatency: Boolean
  val audioBackend: String
  val latencyMs: Double
  val routeKind: String
  val routeName: String
  var gain: Float
  var reverbMix: Float
  var echoMix: Float
  var gateEnabled: Boolean
  var muted: Boolean

  fun setSpace(name: String)
  fun pushSettings()
  fun start()
  fun stop()
}
