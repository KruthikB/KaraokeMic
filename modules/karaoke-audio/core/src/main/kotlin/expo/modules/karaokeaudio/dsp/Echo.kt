package expo.modules.karaokeaudio.dsp

import kotlin.math.abs

/** Single repeating delay. [mix] is the wet amount, 0 to 1. */
class Echo(private val sampleRate: Int) {
  var mix: Float = 0f
  var feedback: Float = 0.28f
  var delaySeconds: Float = 0.18f

  private val maxDelay: Int = (sampleRate * 1.25f).toInt().coerceAtLeast(8)
  private val buffer = FloatArray(maxDelay)
  private var writeIndex = 0

  fun process(input: Float): Float {
    val delaySamples = (delaySeconds * sampleRate).toInt().coerceIn(1, maxDelay - 1)
    var readIndex = writeIndex - delaySamples
    if (readIndex < 0) {
      readIndex += maxDelay
    }
    val delayed = buffer[readIndex]
    val feedbackGain = feedback.coerceIn(0f, 0.75f)
    val written = input + delayed * feedbackGain
    buffer[writeIndex] = if (abs(written) < 1e-8f) 0f else written
    writeIndex += 1
    if (writeIndex >= maxDelay) {
      writeIndex = 0
    }
    val wet = mix.coerceIn(0f, 1f)
    if (wet < 0.001f) {
      return input
    }
    return input * (1f - wet) + delayed * wet
  }

  fun reset() {
    buffer.fill(0f)
    writeIndex = 0
  }
}
