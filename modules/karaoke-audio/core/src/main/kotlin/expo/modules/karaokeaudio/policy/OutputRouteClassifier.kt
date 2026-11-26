package expo.modules.karaokeaudio.policy

/**
 * Device type ints match android.media.AudioDeviceInfo. They are copied here
 * so the routing rules can be tested without the Android SDK.
 */
object OutputRouteClassifier {
  const val TYPE_BUILTIN_EARPIECE = 1
  const val TYPE_BUILTIN_SPEAKER = 2
  const val TYPE_WIRED_HEADSET = 3
  const val TYPE_WIRED_HEADPHONES = 4
  const val TYPE_LINE_ANALOG = 5
  const val TYPE_LINE_DIGITAL = 6
  const val TYPE_BLUETOOTH_SCO = 7
  const val TYPE_BLUETOOTH_A2DP = 8
  const val TYPE_HDMI = 9
  const val TYPE_HDMI_ARC = 10
  const val TYPE_USB_DEVICE = 11
  const val TYPE_USB_ACCESSORY = 12
  const val TYPE_DOCK = 13
  const val TYPE_AUX_LINE = 19
  const val TYPE_USB_HEADSET = 22
  const val TYPE_BUILTIN_SPEAKER_SAFE = 24
  const val TYPE_BLE_HEADSET = 26
  const val TYPE_BLE_SPEAKER = 27
  const val TYPE_HDMI_EARC = 29
  const val TYPE_BLE_BROADCAST = 30

  const val BLUETOOTH = "bluetooth"
  const val BLUETOOTH_CALL = "bluetoothCall"
  const val WIRED = "wired"
  const val PHONE = "phone"
  const val UNKNOWN = "unknown"

  fun classify(outputType: Int): String {
    return when (outputType) {
      TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> BLUETOOTH
      TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_LINE_ANALOG, TYPE_LINE_DIGITAL,
      TYPE_USB_DEVICE, TYPE_USB_ACCESSORY, TYPE_USB_HEADSET, TYPE_AUX_LINE,
      TYPE_HDMI, TYPE_HDMI_ARC, TYPE_HDMI_EARC, TYPE_DOCK -> WIRED
      TYPE_BLUETOOTH_SCO -> BLUETOOTH_CALL
      TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER_SAFE -> PHONE
      else -> UNKNOWN
    }
  }

  /**
   * Media output for the vocal monitor. Stereo Bluetooth and cables win.
   * SCO (phone-call Bluetooth) is never chosen.
   */
  fun prefer(outputTypes: List<Int>): Int? {
    val rank = intArrayOf(
      TYPE_BLUETOOTH_A2DP,
      TYPE_BLE_SPEAKER,
      TYPE_BLE_HEADSET,
      TYPE_USB_HEADSET,
      TYPE_WIRED_HEADPHONES,
      TYPE_WIRED_HEADSET,
      TYPE_USB_DEVICE,
      TYPE_AUX_LINE,
      TYPE_LINE_ANALOG,
      TYPE_LINE_DIGITAL,
      TYPE_HDMI,
      TYPE_HDMI_ARC,
      TYPE_HDMI_EARC,
      TYPE_DOCK,
      TYPE_USB_ACCESSORY,
      TYPE_BUILTIN_SPEAKER,
    )
    val available = outputTypes.toSet()
    return rank.firstOrNull { it in available }
  }
}
