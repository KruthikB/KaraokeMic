package expo.modules.karaokeaudio.dsp

import kotlin.math.abs
import kotlin.math.tanh

/** Keeps the speaker from getting a hard digital clip when reverb and gain stack up. */
class SoftLimiter {
  fun process(sample: Float): Float {
    val magnitude = abs(sample)
    if (magnitude <= KNEE) {
      return sample
    }
    val sign = if (sample < 0f) -1f else 1f
    val over = magnitude - KNEE
    val shaped = KNEE + HEADROOM * tanh(over / HEADROOM)
    return sign * shaped
  }

  private companion object {
    const val KNEE = 0.9f
    const val HEADROOM = 0.099f
  }
}
