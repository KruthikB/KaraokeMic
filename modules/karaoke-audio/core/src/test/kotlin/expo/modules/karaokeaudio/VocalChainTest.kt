package expo.modules.karaokeaudio

import expo.modules.karaokeaudio.dsp.Echo
import expo.modules.karaokeaudio.dsp.Level
import expo.modules.karaokeaudio.dsp.NoiseGate
import expo.modules.karaokeaudio.dsp.OnePoleHighpass
import expo.modules.karaokeaudio.dsp.SchroederReverb
import expo.modules.karaokeaudio.dsp.SoftLimiter
import expo.modules.karaokeaudio.dsp.VocalProcessor
import expo.modules.karaokeaudio.policy.MediaMixPolicy
import expo.modules.karaokeaudio.policy.OutputRouteClassifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VocalChainTest {
  @Test
  fun echoRepeatsAnImpulseOneDelayLater() {
    val rate = 48_000
    val echo = Echo(rate)
    echo.mix = 1f
    echo.feedback = 0f
    echo.delaySeconds = 100f / rate
    val output = FloatArray(120)
    for (index in output.indices) {
      output[index] = echo.process(if (index == 0) 1f else 0f)
    }
    assertEquals(0f, output[0], 0.0001f)
    assertEquals(1f, output[100], 0.0001f)
    assertTrue(abs(output[50]) < 0.0001f)
  }

  @Test
  fun dryEchoLeavesTheInputUntouched() {
    val echo = Echo(48_000)
    echo.mix = 0f
    assertEquals(0.25f, echo.process(0.25f), 0.0001f)
  }

  @Test
  fun gateOpensForASingerAndClosesForRoomNoise() {
    val gate = NoiseGate(48_000)
    var quiet = 0f
    repeat(24_000) {
      quiet = gate.process(0.002f)
    }
    assertTrue(abs(quiet) < 0.0002f, "quiet sample was $quiet")

    gate.reset()
    var loud = 0f
    repeat(2_400) {
      loud = gate.process(0.25f)
    }
    assertTrue(loud > 0.2f, "loud sample was $loud")
  }

  @Test
  fun disabledGatePassesAQuietSample() {
    val gate = NoiseGate(48_000)
    gate.enabled = false
    assertEquals(0.002f, gate.process(0.002f), 0.00001f)
  }

  @Test
  fun highpassRemovesDcAndKeepsAVocalTone() {
    val highpass = OnePoleHighpass(48_000, 90f)
    var last = 1f
    repeat(8_000) {
      last = highpass.process(0.5f)
    }
    assertTrue(abs(last) < 0.02f, "DC leftover was $last")

    val tone = OnePoleHighpass(48_000, 90f)
    var inputEnergy = 0.0
    var outputEnergy = 0.0
    repeat(4_800) { index ->
      val sample = sin(2.0 * PI * 1000.0 * index / 48_000.0).toFloat()
      val filtered = tone.process(sample)
      inputEnergy += sample * sample
      outputEnergy += filtered * filtered
    }
    assertTrue(outputEnergy > inputEnergy * 0.5)
  }

  @Test
  fun limiterHoldsPeaksUnderFullScale() {
    val limiter = SoftLimiter()
    assertEquals(0.4f, limiter.process(0.4f), 0.0001f)
    val hot = limiter.process(10f)
    assertTrue(hot < 1f && hot > 0.9f, "hot peak was $hot")
    val negative = limiter.process(-4f)
    assertTrue(negative > -1f && negative < -0.9f, "negative peak was $negative")
  }

  @Test
  fun reverbCreatesATailAndStaysFinite() {
    val dry = SchroederReverb(48_000)
    dry.mix = 0f
    assertEquals(0.3f, dry.process(0.3f), 0.0001f)

    val reverb = SchroederReverb(48_000)
    reverb.mix = 1f
    reverb.decay = 0.82f
    reverb.room = 0.8f
    reverb.damp = 0.3f
    var tail = 0.0
    repeat(4_000) { index ->
      val sample = reverb.process(if (index == 0) 1f else 0f)
      assertFalse(sample.isNaN())
      if (index > 300) {
        tail += abs(sample)
      }
    }
    assertTrue(tail > 0.05, "reverb tail energy was $tail")
  }

  @Test
  fun processorSilenceAndMuteStaySilentAndPeaksStayLimited() {
    val vocal = VocalProcessor(48_000)
    vocal.gain = 0f
    val input = FloatArray(256) { 0.5f }
    val silent = FloatArray(256)
    vocal.process(input, silent, input.size)
    assertTrue(silent.all { abs(it) < 0.0001f })

    vocal.gain = 1.2f
    vocal.muted = true
    vocal.reverbMix = 0.9f
    vocal.echoMix = 0.4f
    vocal.setSpace("hall")
    vocal.process(input, silent, input.size)
    assertTrue(silent.all { it == 0f })

    vocal.muted = false
    vocal.reverbMix = 0.55f
    vocal.echoMix = 0.3f
    val sine = FloatArray(4_800) { index ->
      sin(2.0 * PI * 220.0 * index / 48_000.0).toFloat()
    }
    val wet = FloatArray(sine.size)
    vocal.process(sine, wet, sine.size)
    assertTrue(wet.all { !it.isNaN() && abs(it) <= 1f })
  }

  @Test
  fun levelMeterReportsRmsAndPeak() {
    val samples = floatArrayOf(0f, 0.5f, -0.5f, 0f)
    val (rms, peak) = Level.rmsAndPeak(samples, samples.size)
    assertEquals(0.5f, peak, 0.0001f)
    assertEquals(sqrt(0.125f), rms, 0.0001f)
    assertEquals(0f to 0f, Level.rmsAndPeak(samples, 0))
  }
}

class RoutingPolicyTest {
  @Test
  fun classifiesSpeakersTheWayTheBannerExpects() {
    assertEquals("bluetooth", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_BLUETOOTH_A2DP))
    assertEquals("bluetooth", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_BLE_SPEAKER))
    assertEquals("wired", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_USB_HEADSET))
    assertEquals("wired", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_WIRED_HEADPHONES))
    assertEquals("wired", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_AUX_LINE))
    assertEquals("wired", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_USB_DEVICE))
    assertEquals("bluetoothCall", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_BLUETOOTH_SCO))
    assertEquals("phone", OutputRouteClassifier.classify(OutputRouteClassifier.TYPE_BUILTIN_SPEAKER))
    assertEquals("unknown", OutputRouteClassifier.classify(0))
  }

  @Test
  fun prefersStereoBluetoothOrACableAndNeverCallAudio() {
    val both = OutputRouteClassifier.prefer(
      listOf(
        OutputRouteClassifier.TYPE_BUILTIN_SPEAKER,
        OutputRouteClassifier.TYPE_BLUETOOTH_SCO,
        OutputRouteClassifier.TYPE_BLUETOOTH_A2DP,
      ),
    )
    assertEquals(OutputRouteClassifier.TYPE_BLUETOOTH_A2DP, both)
    assertNotEquals(OutputRouteClassifier.TYPE_BLUETOOTH_SCO, both)

    assertEquals(
      OutputRouteClassifier.TYPE_USB_HEADSET,
      OutputRouteClassifier.prefer(
        listOf(OutputRouteClassifier.TYPE_BUILTIN_SPEAKER, OutputRouteClassifier.TYPE_USB_HEADSET),
      ),
    )
    assertEquals(
      OutputRouteClassifier.TYPE_BUILTIN_SPEAKER,
      OutputRouteClassifier.prefer(
        listOf(OutputRouteClassifier.TYPE_BLUETOOTH_SCO, OutputRouteClassifier.TYPE_BUILTIN_SPEAKER),
      ),
    )
    assertNull(OutputRouteClassifier.prefer(listOf(OutputRouteClassifier.TYPE_BLUETOOTH_SCO)))
  }

  @Test
  fun deviceTypeNumbersMatchAndroid() {
    assertEquals(8, OutputRouteClassifier.TYPE_BLUETOOTH_A2DP)
    assertEquals(7, OutputRouteClassifier.TYPE_BLUETOOTH_SCO)
    assertEquals(22, OutputRouteClassifier.TYPE_USB_HEADSET)
    assertEquals(19, OutputRouteClassifier.TYPE_AUX_LINE)
    assertEquals(2, OutputRouteClassifier.TYPE_BUILTIN_SPEAKER)
  }

  @Test
  fun mixPolicyDoesNotPauseOrDuckMusicAndDoesNotStartSco() {
    assertFalse(MediaMixPolicy.requestsAudioFocus)
    assertFalse(MediaMixPolicy.ducksOtherApps)
    assertFalse(MediaMixPolicy.startsBluetoothSco)
    assertEquals("USAGE_MEDIA", MediaMixPolicy.audioUsage)
    assertEquals("CONTENT_TYPE_MUSIC", MediaMixPolicy.contentType)
    assertEquals("MODE_NORMAL", MediaMixPolicy.audioMode)
  }
}
