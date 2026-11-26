package expo.modules.karaokeaudio.dsp

import kotlin.math.abs
import kotlin.math.sqrt

object Level {
  fun rmsAndPeak(samples: FloatArray, count: Int): Pair<Float, Float> {
    if (count <= 0) {
      return 0f to 0f
    }
    var sum = 0.0
    var peak = 0f
    val frames = count.coerceAtMost(samples.size)
    for (index in 0 until frames) {
      val sample = samples[index]
      val magnitude = abs(sample)
      sum += (magnitude * magnitude).toDouble()
      if (magnitude > peak) {
        peak = magnitude
      }
    }
    val rms = sqrt(sum / frames).toFloat()
    return rms to peak
  }
}
