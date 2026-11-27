package expo.modules.karaokeaudio

/**
 * Names the UI shows for the engine that is actually running.
 * The C++ side uses the same AAudio and OpenSL strings.
 */
object AudioBackend {
  const val AAUDIO_LOW_LATENCY = "AAudio Low-Latency"
  const val AAUDIO = "AAudio"
  const val OPENSLES = "OpenSL ES"
  const val AUDIOTRACK_FALLBACK = "AudioTrack Fallback"
  const val WEB_AUDIO = "Web Audio"
  const val AVAUDIO_ENGINE = "AVAudioEngine"
}

object AudioLatency {
  /** Round-trip estimate from the buffer the stream is really using. */
  fun milliseconds(bufferFrames: Int, sampleRate: Int): Double {
    if (bufferFrames <= 0 || sampleRate <= 0) {
      return 0.0
    }
    return bufferFrames * 1000.0 / sampleRate
  }
}
