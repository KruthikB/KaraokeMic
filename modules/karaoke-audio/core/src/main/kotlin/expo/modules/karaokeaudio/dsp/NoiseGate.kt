package expo.modules.karaokeaudio.dsp

import kotlin.math.abs
import kotlin.math.exp

/**
 * Downward gate. Closes when the singer is quiet so room noise and the start
 * of acoustic feedback are turned down between phrases. A loud howl is above
 * the threshold, so the gate opens for it — it is not an echo canceller.
 */
class NoiseGate(sampleRate: Int) {
  var enabled: Boolean = true
  var threshold: Float = 0.02f

  private val attackEnvelope: Float = coefficient(sampleRate, 0.004f)
  private val releaseEnvelope: Float = coefficient(sampleRate, 0.130f)
  private val attackGain: Float = coefficient(sampleRate, 0.003f)
  private val releaseGain: Float = coefficient(sampleRate, 0.090f)
  private val holdSamples: Int = (0.045f * sampleRate).toInt().coerceAtLeast(1)

  private var envelope = 0f
  private var gain = 0f
  private var hold = 0

  fun process(sample: Float): Float {
    if (!enabled) {
      gain = 1f
      return sample
    }
    val level = abs(sample)
    val envelopeCoefficient = if (level > envelope) attackEnvelope else releaseEnvelope
    envelope += (level - envelope) * envelopeCoefficient
    if (envelope >= threshold) {
      hold = holdSamples
    } else if (hold > 0) {
      hold -= 1
    }
    val target = if (envelope >= threshold || hold > 0) 1f else 0f
    val gainCoefficient = if (target > gain) attackGain else releaseGain
    gain += (target - gain) * gainCoefficient
    return sample * gain
  }

  fun reset() {
    envelope = 0f
    gain = 0f
    hold = 0
  }

  private fun coefficient(sampleRate: Int, seconds: Float): Float {
    val frames = (seconds * sampleRate).coerceAtLeast(1f)
    return (1.0 - exp(-1.0 / frames)).toFloat()
  }
}
