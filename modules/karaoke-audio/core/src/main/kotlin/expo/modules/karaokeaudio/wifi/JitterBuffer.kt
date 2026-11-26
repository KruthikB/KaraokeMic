package expo.modules.karaokeaudio.wifi

import kotlin.math.abs

/**
 * Tiny playout buffer for 5–10 ms Wi-Fi frames.
 *
 * The network thread calls [push]. The speaker thread calls [pull]. A short
 * lock covers one frame copy. The direct-speaker Oboe path does not use this
 * buffer; that path is a lock-free ring in C++.
 *
 * Playout waits until [targetMs] of audio has arrived, then plays in sequence
 * order. A missing sequence is skipped once a newer frame proves it is gone.
 * If the queue grows past [maxMs], the oldest frames are dropped so a stall
 * cannot turn into a long delay.
 */
class JitterBuffer(
  private val sampleRate: Int,
  private val targetMs: Int = 15,
  private val maxMs: Int = 30,
) {
  init {
    require(sampleRate > 0)
    require(targetMs in 1..maxMs)
  }

  private val lock = Any()
  private val slots = arrayOfNulls<ShortArray>(CAPACITY)
  private val slotSequence = LongArray(CAPACITY) { EMPTY }

  private var playSequence = 0L
  private var playOffset = 0
  private var primed = false
  private var lastArrivalNs = 0L
  private var lastPacketSamples = 0
  private var jitterNs = 0.0
  private var receivedPackets = 0L
  private var lostPackets = 0L

  fun push(sequence: Long, samples: ShortArray, arrivalNs: Long) {
    if (samples.isEmpty() || samples.size > WifiMicPacket.MAX_FRAMES) {
      return
    }
    synchronized(lock) {
      if (primed && sequence < playSequence) {
        return
      }
      val index = indexOf(sequence)
      val occupant = slotSequence[index]
      if (occupant != EMPTY && occupant != sequence && isLive(occupant) && isLive(sequence)) {
        val incomingIsOlder = sequence < occupant
        if (!incomingIsOlder) {
          return
        }
      }
      slotSequence[index] = sequence
      slots[index] = samples.copyOf()
      receivedPackets += 1
      if (lastArrivalNs != 0L && lastPacketSamples > 0) {
        val expectedNs = lastPacketSamples * 1_000_000_000.0 / sampleRate
        val delta = abs((arrivalNs - lastArrivalNs) - expectedNs)
        jitterNs += (delta - jitterNs) / 16.0
      }
      lastArrivalNs = arrivalNs
      lastPacketSamples = samples.size
      if (!primed && storedSamples() >= targetSamples()) {
        playSequence = oldestSequence() ?: sequence
        playOffset = 0
        primed = true
      }
      trimToMax()
    }
  }

  /** Fills [output] completely. Missing audio becomes silence. */
  fun pull(output: ShortArray, frames: Int) {
    var written = 0
    synchronized(lock) {
      while (written < frames) {
        if (!primed) {
          output[written] = 0
          written += 1
          continue
        }
        val packet = packetAt(playSequence)
        if (packet != null && playOffset < packet.size) {
          output[written] = packet[playOffset]
          playOffset += 1
          written += 1
          if (playOffset >= packet.size) {
            clearSlot(playSequence)
            playSequence += 1
            playOffset = 0
          }
          continue
        }
        val next = smallestSequenceAbove(playSequence)
        if (next == null) {
          while (written < frames) {
            output[written] = 0
            written += 1
          }
          return
        }
        lostPackets += next - playSequence
        clearSlot(playSequence)
        playSequence = next
        playOffset = 0
      }
    }
  }

  fun depthMs(): Double {
    synchronized(lock) {
      return storedSamples() * 1000.0 / sampleRate
    }
  }

  fun jitterMs(): Double {
    synchronized(lock) {
      return jitterNs / 1_000_000.0
    }
  }

  fun packetsReceived(): Long {
    synchronized(lock) {
      return receivedPackets
    }
  }

  fun packetsLost(): Long {
    synchronized(lock) {
      return lostPackets
    }
  }

  fun isPrimed(): Boolean {
    synchronized(lock) {
      return primed
    }
  }

  fun reset() {
    synchronized(lock) {
      for (index in slots.indices) {
        slots[index] = null
        slotSequence[index] = EMPTY
      }
      playSequence = 0
      playOffset = 0
      primed = false
      lastArrivalNs = 0
      lastPacketSamples = 0
      jitterNs = 0.0
      receivedPackets = 0
      lostPackets = 0
    }
  }

  private fun trimToMax() {
    val limit = maxMs * sampleRate / 1000
    while (primed && storedSamples() > limit) {
      clearSlot(playSequence)
      playSequence += 1
      playOffset = 0
    }
  }

  private fun storedSamples(): Int {
    var total = 0
    for (index in slots.indices) {
      val sequence = slotSequence[index]
      if (!isLive(sequence)) {
        continue
      }
      val packet = slots[index] ?: continue
      total += if (primed && sequence == playSequence) {
        (packet.size - playOffset).coerceAtLeast(0)
      } else {
        packet.size
      }
    }
    return total
  }

  private fun isLive(sequence: Long): Boolean {
    if (sequence == EMPTY) {
      return false
    }
    return !primed || sequence >= playSequence
  }

  private fun oldestSequence(): Long? {
    var oldest: Long? = null
    for (index in slotSequence.indices) {
      val sequence = slotSequence[index]
      if (!isLive(sequence)) {
        continue
      }
      if (oldest == null || sequence < oldest) {
        oldest = sequence
      }
    }
    return oldest
  }

  private fun smallestSequenceAbove(sequence: Long): Long? {
    var found: Long? = null
    for (index in slotSequence.indices) {
      val candidate = slotSequence[index]
      if (candidate == EMPTY || candidate <= sequence) {
        continue
      }
      if (found == null || candidate < found) {
        found = candidate
      }
    }
    return found
  }

  private fun packetAt(sequence: Long): ShortArray? {
    val index = indexOf(sequence)
    if (slotSequence[index] != sequence) {
      return null
    }
    return slots[index]
  }

  private fun clearSlot(sequence: Long) {
    val index = indexOf(sequence)
    if (slotSequence[index] == sequence) {
      slotSequence[index] = EMPTY
      slots[index] = null
    }
  }

  private fun indexOf(sequence: Long): Int {
    return Math.floorMod(sequence, CAPACITY.toLong()).toInt()
  }

  private fun targetSamples(): Int {
    return targetMs * sampleRate / 1000
  }

  private companion object {
    const val CAPACITY = 64
    const val EMPTY = Long.MIN_VALUE
  }
}
