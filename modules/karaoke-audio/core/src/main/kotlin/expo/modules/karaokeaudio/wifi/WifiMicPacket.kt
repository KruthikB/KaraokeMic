package expo.modules.karaokeaudio.wifi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw PCM frames for the two-phone Wi-Fi mic.
 *
 * Header fields are big-endian. PCM samples are little-endian int16, mono.
 * A 240-sample frame at 48 kHz is 5 ms, which stays under a typical Wi-Fi MTU.
 *
 * ```
 *  0  magic          uint32  0x534D4943 ("SMIC")
 *  4  version        uint8   1
 *  5  type           uint8   1 audio, 2 ack
 * Audio:
 *  6  sampleRate     uint16
 *  8  sequence       uint32
 * 12  timestampUs    uint32  sender monotonic clock, low 32 bits
 * 16  frameCount     uint16
 * 18  reserved       uint16
 * 20  pcm            int16 little-endian, frameCount samples
 * Ack:
 *  6  flags          uint16
 *  8  sequence       uint32  latest audio sequence
 * 12  timestampUs    uint32  echoed sender timestamp
 * 16  bufferTenths   uint16  jitter-buffer depth in 0.1 ms
 * 18  jitterTenths   uint16  inter-arrival jitter in 0.1 ms
 * ```
 */
object WifiMicPacket {
  const val MAGIC = 0x534D4943
  const val VERSION = 1
  const val TYPE_AUDIO = 1
  const val TYPE_ACK = 2
  const val AUDIO_HEADER = 20
  const val ACK_SIZE = 20
  const val MAX_FRAMES = 480
  const val DEFAULT_PORT = 49152
  const val DEFAULT_SAMPLE_RATE = 48_000
  const val DEFAULT_FRAME_SAMPLES = 240
  const val SERVICE_TYPE = "_stagemic._udp."

  data class Audio(
    val sequence: Long,
    val timestampUs: Long,
    val sampleRate: Int,
    val samples: ShortArray,
  )

  data class Ack(
    val sequence: Long,
    val timestampUs: Long,
    val bufferDepthMs: Double,
    val jitterMs: Double,
  )

  fun encodeAudio(frame: Audio): ByteArray {
    val size = encodedAudioSize(frame.samples.size)
    val bytes = ByteArray(size)
    encodeAudioInto(frame, bytes)
    return bytes
  }

  fun encodedAudioSize(sampleCount: Int): Int {
    return AUDIO_HEADER + sampleCount.coerceIn(0, MAX_FRAMES) * 2
  }

  fun encodeAudioInto(frame: Audio, destination: ByteArray): Int {
    val count = frame.samples.size.coerceIn(0, MAX_FRAMES)
    val size = AUDIO_HEADER + count * 2
    if (destination.size < size) {
      return -1
    }
    val buffer = ByteBuffer.wrap(destination, 0, size).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(MAGIC)
    buffer.put(VERSION.toByte())
    buffer.put(TYPE_AUDIO.toByte())
    buffer.putShort(frame.sampleRate.coerceIn(0, 65_535).toShort())
    buffer.putInt(low32(frame.sequence))
    buffer.putInt(low32(frame.timestampUs))
    buffer.putShort(count.toShort())
    buffer.putShort(0)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    for (index in 0 until count) {
      buffer.putShort(frame.samples[index])
    }
    return size
  }

  fun encodeAck(ack: Ack): ByteArray {
    val bytes = ByteArray(ACK_SIZE)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(MAGIC)
    buffer.put(VERSION.toByte())
    buffer.put(TYPE_ACK.toByte())
    buffer.putShort(0)
    buffer.putInt(low32(ack.sequence))
    buffer.putInt(low32(ack.timestampUs))
    buffer.putShort(tenths(ack.bufferDepthMs))
    buffer.putShort(tenths(ack.jitterMs))
    return bytes
  }

  fun decode(bytes: ByteArray, length: Int = bytes.size): Any? {
    if (length < 6 || length > bytes.size) {
      return null
    }
    val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
    if (buffer.int != MAGIC) {
      return null
    }
    val version = buffer.get().toInt() and 0xFF
    if (version != VERSION) {
      return null
    }
    return when (buffer.get().toInt() and 0xFF) {
      TYPE_AUDIO -> decodeAudio(buffer, length)
      TYPE_ACK -> decodeAck(buffer, length)
      else -> null
    }
  }

  /** Unsigned 32-bit elapsed time, so the clock can wrap without going negative. */
  fun wrappedDeltaUs(earlier: Long, later: Long): Long {
    return (later - earlier) and 0xFFFF_FFFFL
  }

  private fun decodeAudio(buffer: ByteBuffer, length: Int): Audio? {
    if (length < AUDIO_HEADER) {
      return null
    }
    val sampleRate = buffer.short.toInt() and 0xFFFF
    val sequence = buffer.int.toLong() and 0xFFFF_FFFFL
    val timestampUs = buffer.int.toLong() and 0xFFFF_FFFFL
    val count = buffer.short.toInt() and 0xFFFF
    buffer.short
    if (count > MAX_FRAMES || length < AUDIO_HEADER + count * 2) {
      return null
    }
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val samples = ShortArray(count)
    for (index in 0 until count) {
      samples[index] = buffer.short
    }
    return Audio(sequence, timestampUs, sampleRate, samples)
  }

  private fun decodeAck(buffer: ByteBuffer, length: Int): Ack? {
    if (length < ACK_SIZE) {
      return null
    }
    buffer.short
    val sequence = buffer.int.toLong() and 0xFFFF_FFFFL
    val timestampUs = buffer.int.toLong() and 0xFFFF_FFFFL
    val bufferDepthMs = (buffer.short.toInt() and 0xFFFF) / 10.0
    val jitterMs = (buffer.short.toInt() and 0xFFFF) / 10.0
    return Ack(sequence, timestampUs, bufferDepthMs, jitterMs)
  }

  private fun low32(value: Long): Int {
    return (value and 0xFFFF_FFFFL).toInt()
  }

  private fun tenths(milliseconds: Double): Short {
    if (milliseconds.isNaN() || milliseconds.isInfinite() || milliseconds <= 0.0) {
      return 0
    }
    return milliseconds.times(10.0).toInt().coerceIn(0, 65_535).toShort()
  }
}
