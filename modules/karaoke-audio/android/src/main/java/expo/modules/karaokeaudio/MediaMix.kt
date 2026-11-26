package expo.modules.karaokeaudio

import android.media.AudioManager
import android.os.Build
import expo.modules.karaokeaudio.policy.MediaMixPolicy

/**
 * Puts the phone back in normal media mode without taking audio focus and
 * without leaving Bluetooth in the phone-call profile.
 */
internal object MediaMix {
  fun prepare(audioManager: AudioManager) {
    check(!MediaMixPolicy.requestsAudioFocus) { "StageMic must not request audio focus" }
    check(!MediaMixPolicy.startsBluetoothSco) { "StageMic must not start Bluetooth SCO" }
    check(MediaMixPolicy.audioUsage == "USAGE_MEDIA")
    check(MediaMixPolicy.audioMode == "MODE_NORMAL")
    if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
      audioManager.mode = AudioManager.MODE_NORMAL
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        audioManager.clearCommunicationDevice()
      } catch (_: Throwable) {
        // Some devices refuse this outside a communication session.
      }
    }
    @Suppress("DEPRECATION")
    if (audioManager.isBluetoothScoOn) {
      audioManager.stopBluetoothSco()
      audioManager.isBluetoothScoOn = false
    }
  }
}
