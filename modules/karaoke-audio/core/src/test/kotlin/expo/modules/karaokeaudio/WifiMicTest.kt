package expo.modules.karaokeaudio

import expo.modules.karaokeaudio.wifi.JitterBuffer
import expo.modules.karaokeaudio.wifi.WifiMicPacket
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiMicPacketTest {
  @Test
  fun audioFrameRoundTripsIncludingSilenceAndFullScale() {
    val samples = shortArrayOf(0, 1, -1, 32_767, -32_768, 1_234)
    val encoded = WifiMicPacket.encodeAudio(
      WifiMicPacket.Audio(
        sequence = 7,
        timestampUs = 0xABCDE,
        sampleRate = 48_000,
        samples = samples,
      ),
    )
    assertEquals(WifiMicPacket.AUDIO_HEADER + samples.size * 2, encoded.size)
    val decoded = assertIs<WifiMicPacket.Audio>(WifiMicPacket.decode(encoded))
    assertEquals(7, decoded.sequence)
    assertEquals(0xABCDE, decoded.timestampUs)
    assertEquals(48_000, decoded.sampleRate)
    assertContentEquals(samples, decoded.samples)
  }

  @Test
  fun encodeIntoReusesACallerBuffer() {
    val samples = ShortArray(WifiMicPacket.DEFAULT_FRAME_SAMPLES) { index -> index.toShort() }
    val frame = WifiMicPacket.Audio(3, 99, 48_000, samples)
    val destination = ByteArray(WifiMicPacket.encodedAudioSize(samples.size))
    val size = WifiMicPacket.encodeAudioInto(frame, destination)
    assertEquals(destination.size, size)
    val decoded = assertIs<WifiMicPacket.Audio>(WifiMicPacket.decode(destination, size))
    assertEquals(3, decoded.sequence)
    assertContentEquals(samples, decoded.samples)
  }

  @Test
  fun ackRoundTripsBufferAndJitterInTenths() {
    val encoded = WifiMicPacket.encodeAck(
      WifiMicPacket.Ack(
        sequence = 12,
        timestampUs = 50_000,
        bufferDepthMs = 15.0,
        jitterMs = 1.2,
      ),
    )
    assertEquals(WifiMicPacket.ACK_SIZE, encoded.size)
    val decoded = assertIs<WifiMicPacket.Ack>(WifiMicPacket.decode(encoded))
    assertEquals(12, decoded.sequence)
    assertEquals(50_000, decoded.timestampUs)
    assertEquals(15.0, decoded.bufferDepthMs, 0.05)
    assertEquals(1.2, decoded.jitterMs, 0.05)
  }

  @Test
  fun rejectsABadMagicAShortPacketAndAnUnknownType() {
    assertNull(WifiMicPacket.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    val audio = WifiMicPacket.encodeAudio(WifiMicPacket.Audio(1, 1, 48_000, shortArrayOf(4)))
    assertNull(WifiMicPacket.decode(audio, WifiMicPacket.AUDIO_HEADER - 1))
    val broken = audio.copyOf()
    broken[5] = 9
    assertNull(WifiMicPacket.decode(broken))
  }

  @Test
  fun wrappedClockDeltaSurvivesThe32BitRollover() {
    val earlier = 0xFFFF_FFF0L
    val later = 0x10L
    assertEquals(0x20L, WifiMicPacket.wrappedDeltaUs(earlier, later))
    assertEquals(2_000L, WifiMicPacket.wrappedDeltaUs(1_000, 3_000))
  }
}

class JitterBufferTest {
  @Test
  fun staysSilentUntilTheTargetDepthArrives() {
    val buffer = JitterBuffer(sampleRate = 48_000, targetMs = 15, maxMs = 40)
    val frame = ShortArray(240) { 1000 }
    buffer.push(0, frame, 0)
    buffer.push(1, frame, 5_000_000)
    assertFalse(buffer.isPrimed())
    val output = ShortArray(240)
    buffer.pull(output, output.size)
    assertTrue(output.all { it == 0.toShort() })
  }

  @Test
  fun playsReorderedFramesInSequenceOrder() {
    val buffer = JitterBuffer(sampleRate = 48_000, targetMs = 10, maxMs = 40)
    buffer.push(1, ShortArray(240) { 2 }, 5_000_000)
    buffer.push(0, ShortArray(240) { 1 }, 6_000_000)
    assertTrue(buffer.isPrimed())
    val first = ShortArray(240)
    val second = ShortArray(240)
    buffer.pull(first, first.size)
    buffer.pull(second, second.size)
    assertTrue(first.all { it == 1.toShort() })
    assertTrue(second.all { it == 2.toShort() })
    assertEquals(0, buffer.packetsLost())
  }

  @Test
  fun countsAMissingFrameOnceALaterFrameArrives() {
    val buffer = JitterBuffer(sampleRate = 48_000, targetMs = 5, maxMs = 40)
    buffer.push(0, ShortArray(240) { 4 }, 0)
    assertTrue(buffer.isPrimed())
    val first = ShortArray(240)
    buffer.pull(first, first.size)
    buffer.push(2, ShortArray(240) { 9 }, 10_000_000)
    val second = ShortArray(240)
    buffer.pull(second, second.size)
    assertTrue(second.all { it == 9.toShort() })
    assertEquals(1, buffer.packetsLost())
  }

  @Test
  fun dropsAudioThatArrivesAfterItWasAlreadySkipped() {
    val buffer = JitterBuffer(sampleRate = 48_000, targetMs = 5, maxMs = 40)
    buffer.push(0, ShortArray(240) { 4 }, 0)
    val played = ShortArray(240)
    buffer.pull(played, played.size)
    buffer.push(0, ShortArray(240) { 7 }, 1_000)
    val next = ShortArray(240)
    buffer.pull(next, next.size)
    assertTrue(next.all { it == 0.toShort() })
  }

  @Test
  fun capsTheQueueSoAStallCannotGrowTheDelay() {
    val buffer = JitterBuffer(sampleRate = 48_000, targetMs = 5, maxMs = 15)
    val frame = ShortArray(240) { 3 }
    for (sequence in 0 until 10) {
      buffer.push(sequence.toLong(), frame, sequence * 5_000_000L)
    }
    assertTrue(buffer.depthMs() <= 15.01, "depth was ${buffer.depthMs()}")
    assertTrue(buffer.depthMs() > 0.0)
  }

  @Test
  fun jitterGrowsWhenArrivalsAreUneven() {
    val buffer = JitterBuffer(sampleRate = 48_000, targetMs = 5, maxMs = 80)
    val frame = ShortArray(240) { 1 }
    buffer.push(0, frame, 0)
    buffer.push(1, frame, 5_000_000)
    val even = buffer.jitterMs()
    buffer.push(2, frame, 20_000_000)
    assertTrue(buffer.jitterMs() > even)
    assertTrue(abs(buffer.jitterMs()) < 50.0)
  }
}

class AudioBackendTest {
  @Test
  fun latencyComesFromBufferFramesAndSampleRate() {
    assertEquals(0.0, AudioLatency.milliseconds(0, 48_000))
    assertEquals(0.0, AudioLatency.milliseconds(192, 0))
    assertEquals(10.0, AudioLatency.milliseconds(480, 48_000), 0.001)
    assertEquals(4.0, AudioLatency.milliseconds(192, 48_000), 0.001)
  }

  @Test
  fun backendNamesMatchTheLabelsOnScreen() {
    assertEquals("AAudio Low-Latency", AudioBackend.AAUDIO_LOW_LATENCY)
    assertEquals("OpenSL ES", AudioBackend.OPENSLES)
    assertEquals("AudioTrack Fallback", AudioBackend.AUDIOTRACK_FALLBACK)
  }
}
