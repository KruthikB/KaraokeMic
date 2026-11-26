package expo.modules.karaokeaudio.dsp

import kotlin.math.PI
import kotlin.math.abs

/**
 * Gentle vocal high-pass. Removes rumble that otherwise turns into low howl
 * when a speaker is close to the phone.
 */
class OnePoleHighpass(sampleRate: Int, cutoffHz: Float) {
  private val alpha: Float
  private var previousInput = 0f
  private var previousOutput = 0f

  init {
    val dt = 1.0 / sampleRate.coerceAtLeast(1)
    val rc = 1.0 / (2.0 * PI * cutoffHz.coerceAtLeast(1f))
    alpha = (rc / (rc + dt)).toFloat()
  }

  fun process(sample: Float): Float {
    val output = alpha * (previousOutput + sample - previousInput)
    previousInput = sample
    previousOutput = if (abs(output) < 1e-8f) 0f else output
    return previousOutput
  }

  fun reset() {
    previousInput = 0f
    previousOutput = 0f
  }
}
