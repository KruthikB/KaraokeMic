package expo.modules.karaokeaudio.policy

/**
 * How StageMic shares the phone with Spotify, YouTube, Apple Music, and the rest.
 *
 * Requesting audio focus would pause those apps. A "may duck" focus would
 * turn them down. Call mode (MODE_IN_COMMUNICATION) plus Bluetooth SCO would
 * drop the speaker into mono phone-call quality. None of that is acceptable
 * for karaoke, so the engine follows this policy and does not ask for focus.
 */
object MediaMixPolicy {
  const val requestsAudioFocus: Boolean = false
  const val ducksOtherApps: Boolean = false
  const val startsBluetoothSco: Boolean = false
  const val audioUsage: String = "USAGE_MEDIA"
  const val contentType: String = "CONTENT_TYPE_MUSIC"
  const val audioMode: String = "MODE_NORMAL"
}
