package expo.modules.karaokeaudio.dsp

import kotlin.math.abs

/**
 * Small Schroeder reverb (eight damped combs into two all-passes).
 * [mix] is the wet amount. [decay] must stay under 1 or the tail rings forever.
 */
class SchroederReverb(sampleRate: Int) {
  var mix: Float = 0f
  var room: Float = 0.25f
  var damp: Float = 0.45f
  var decay: Float = 0.55f

  private val combs: List<Comb>
  private val allpasses: List<Allpass>

  init {
    val combDelays = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    val allpassDelays = intArrayOf(225, 556)
    combs = combDelays.map { Comb(scale(it, sampleRate)) }
    allpasses = allpassDelays.map { Allpass(scale(it, sampleRate)) }
  }

  fun process(input: Float): Float {
    val wetMix = mix.coerceIn(0f, 1f)
    if (wetMix < 0.001f) {
      return input
    }
    val feedback = decay.coerceIn(0.15f, 0.92f)
    val damping = damp.coerceIn(0.05f, 0.9f)
    var sum = 0f
    for (comb in combs) {
      sum += comb.process(input, feedback, damping)
    }
    var wet = sum / combs.size
    for (allpass in allpasses) {
      wet = allpass.process(wet)
    }
    val roomGain = 0.35f + room.coerceIn(0f, 1f) * 0.45f
    val wetScaled = wet * roomGain
    return input * (1f - wetMix) + wetScaled * wetMix
  }

  fun reset() {
    combs.forEach { it.reset() }
    allpasses.forEach { it.reset() }
  }

  private fun scale(baseSamplesAt44100: Int, sampleRate: Int): Int {
    return (baseSamplesAt44100 * (sampleRate / 44100f)).toInt().coerceAtLeast(8)
  }

  private class Comb(private val delaySamples: Int) {
    private val buffer = FloatArray(delaySamples)
    private var index = 0
    private var filter = 0f

    fun process(input: Float, feedback: Float, damp: Float): Float {
      val output = buffer[index]
      filter = output * (1f - damp) + filter * damp
      if (abs(filter) < 1e-8f) {
        filter = 0f
      }
      buffer[index] = input + filter * feedback
      index += 1
      if (index >= delaySamples) {
        index = 0
      }
      return output
    }

    fun reset() {
      buffer.fill(0f)
      index = 0
      filter = 0f
    }
  }

  private class Allpass(private val delaySamples: Int) {
    private val buffer = FloatArray(delaySamples)
    private var index = 0

    fun process(input: Float): Float {
      val buffered = buffer[index]
      val output = -input + buffered
      buffer[index] = input + buffered * 0.5f
      index += 1
      if (index >= delaySamples) {
        index = 0
      }
      return output
    }

    fun reset() {
      buffer.fill(0f)
      index = 0
    }
  }
}
