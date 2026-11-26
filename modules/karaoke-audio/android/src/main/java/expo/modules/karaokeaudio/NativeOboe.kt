package expo.modules.karaokeaudio

/**
 * JNI front for libkaraokeaudio.so. The library is optional at runtime: if it
 * did not package, [available] is false and the Kotlin AudioTrack engine runs.
 */
internal object NativeOboe {
  val available: Boolean = try {
    System.loadLibrary("karaokeaudio")
    true
  } catch (_: UnsatisfiedLinkError) {
    false
  }

  fun startDuplex(sampleRate: Int, framesPerBurst: Int, inputDeviceId: Int, outputDeviceId: Int): Boolean {
    if (!available) {
      return false
    }
    return nativeStartDuplex(sampleRate, framesPerBurst, inputDeviceId, outputDeviceId)
  }

  fun startInput(sampleRate: Int, framesPerBurst: Int, inputDeviceId: Int): Boolean {
    if (!available) {
      return false
    }
    return nativeStartInput(sampleRate, framesPerBurst, inputDeviceId)
  }

  fun startOutput(sampleRate: Int, framesPerBurst: Int, outputDeviceId: Int): Boolean {
    if (!available) {
      return false
    }
    return nativeStartOutput(sampleRate, framesPerBurst, outputDeviceId)
  }

  fun stop() {
    if (available) {
      nativeStop()
    }
  }

  fun read(buffer: FloatArray, frames: Int): Int {
    if (!available) {
      return -1
    }
    return nativeRead(buffer, frames)
  }

  fun write(buffer: ShortArray, frames: Int): Int {
    if (!available) {
      return -1
    }
    return nativeWrite(buffer, frames)
  }

  fun setGain(value: Float) { if (available) nativeSetGain(value) }
  fun setReverb(value: Float) { if (available) nativeSetReverb(value) }
  fun setEcho(value: Float) { if (available) nativeSetEcho(value) }
  fun setGate(enabled: Boolean) { if (available) nativeSetGate(enabled) }
  fun setMuted(muted: Boolean) { if (available) nativeSetMuted(muted) }
  fun setSpace(name: String) { if (available) nativeSetSpace(name) }

  fun sampleRate(): Int = if (available) nativeSampleRate() else 0
  fun latencyMs(): Double = if (available) nativeLatencyMs() else 0.0
  fun lowLatency(): Boolean = available && nativeLowLatency()
  fun rms(): Float = if (available) nativeRms() else 0f
  fun peak(): Float = if (available) nativePeak() else 0f
  fun backend(): String = if (available) nativeBackend() else ""
  fun lastError(): String = if (available) nativeLastError() else "Oboe was not packaged in this build."
  fun isRunning(): Boolean = available && nativeIsRunning()

  private external fun nativeStartDuplex(sampleRate: Int, framesPerBurst: Int, inputDeviceId: Int, outputDeviceId: Int): Boolean
  private external fun nativeStartInput(sampleRate: Int, framesPerBurst: Int, inputDeviceId: Int): Boolean
  private external fun nativeStartOutput(sampleRate: Int, framesPerBurst: Int, outputDeviceId: Int): Boolean
  private external fun nativeStop()
  private external fun nativeRead(buffer: FloatArray, frames: Int): Int
  private external fun nativeWrite(buffer: ShortArray, frames: Int): Int
  private external fun nativeSetGain(value: Float)
  private external fun nativeSetReverb(value: Float)
  private external fun nativeSetEcho(value: Float)
  private external fun nativeSetGate(enabled: Boolean)
  private external fun nativeSetMuted(muted: Boolean)
  private external fun nativeSetSpace(name: String)
  private external fun nativeSampleRate(): Int
  private external fun nativeLatencyMs(): Double
  private external fun nativeLowLatency(): Boolean
  private external fun nativeRms(): Float
  private external fun nativePeak(): Float
  private external fun nativeBackend(): String
  private external fun nativeLastError(): String
  private external fun nativeIsRunning(): Boolean
}
