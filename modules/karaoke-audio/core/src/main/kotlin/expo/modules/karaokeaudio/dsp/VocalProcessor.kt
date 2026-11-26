package expo.modules.karaokeaudio.dsp

/**
 * Mono vocal chain used by the Android passthrough thread.
 * Control values are volatile so the UI thread can move a slider without
 * locking the audio thread. The audio thread is the only writer of the
 * effect objects themselves.
 */
class VocalProcessor(sampleRate: Int) {
  @Volatile var gain: Float = 0.85f
  @Volatile var reverbMix: Float = 0.06f
  @Volatile var echoMix: Float = 0f
  @Volatile var gateEnabled: Boolean = true
  @Volatile var muted: Boolean = false
  @Volatile var room: Float = 0.22f
  @Volatile var damp: Float = 0.5f
  @Volatile var decay: Float = 0.5f
  @Volatile var echoDelaySeconds: Float = 0.12f
  @Volatile var echoFeedback: Float = 0.18f

  private val highpass = OnePoleHighpass(sampleRate, 90f)
  private val gate = NoiseGate(sampleRate)
  private val echo = Echo(sampleRate)
  private val reverb = SchroederReverb(sampleRate)
  private val limiter = SoftLimiter()

  fun setSpace(name: String) {
    when (name) {
      "studio" -> {
        room = 0.5f
        damp = 0.32f
        decay = 0.74f
        echoDelaySeconds = 0.16f
        echoFeedback = 0.28f
      }
      "hall" -> {
        room = 0.9f
        damp = 0.2f
        decay = 0.86f
        echoDelaySeconds = 0.28f
        echoFeedback = 0.36f
      }
      else -> {
        room = 0.22f
        damp = 0.5f
        decay = 0.5f
        echoDelaySeconds = 0.12f
        echoFeedback = 0.16f
      }
    }
  }

  fun process(input: FloatArray, output: FloatArray, frames: Int) {
    val gainNow = gain.coerceIn(0f, 1.5f)
    val mutedNow = muted
    gate.enabled = gateEnabled
    echo.mix = echoMix
    echo.delaySeconds = echoDelaySeconds
    echo.feedback = echoFeedback
    reverb.mix = reverbMix
    reverb.room = room
    reverb.damp = damp
    reverb.decay = decay
    val count = frames.coerceAtMost(input.size).coerceAtMost(output.size)
    for (index in 0 until count) {
      var sample = highpass.process(input[index]) * gainNow
      sample = gate.process(sample)
      sample = echo.process(sample)
      sample = reverb.process(sample)
      sample = limiter.process(sample)
      output[index] = if (mutedNow) 0f else sample
    }
  }
}
