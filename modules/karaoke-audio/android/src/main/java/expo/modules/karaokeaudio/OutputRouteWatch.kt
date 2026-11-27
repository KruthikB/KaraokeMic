package expo.modules.karaokeaudio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import expo.modules.karaokeaudio.policy.OutputRouteClassifier

internal object DevicePicker {
  fun builtinMicId(audioManager: AudioManager): Int {
    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id ?: 0
  }

  fun preferredOutput(audioManager: AudioManager): AudioDeviceInfo? {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val chosen = OutputRouteClassifier.prefer(outputs.map { it.type }) ?: return null
    return outputs.firstOrNull { it.type == chosen }
  }
}

/**
 * Reports the media output the vocal monitor is aimed at. SCO is never chosen.
 * A device change also clears call-mode leftovers so stereo media can return.
 */
internal class OutputRouteWatch(
  context: Context,
  private val onRoute: (String, String) -> Unit,
) {
  private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private var callback: AudioDeviceCallback? = null

  fun outputId(): Int = DevicePicker.preferredOutput(audioManager)?.id ?: 0

  fun micId(): Int = DevicePicker.builtinMicId(audioManager)

  fun start() {
    publish()
    val created = object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        MediaMix.prepare(audioManager)
        publish()
      }

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        MediaMix.prepare(audioManager)
        publish()
      }
    }
    callback = created
    audioManager.registerAudioDeviceCallback(created, mainHandler)
  }

  fun stop() {
    callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
    callback = null
  }

  private fun publish() {
    val device = DevicePicker.preferredOutput(audioManager)
    if (device == null) {
      mainHandler.post { onRoute(OutputRouteClassifier.UNKNOWN, "") }
      return
    }
    val kind = OutputRouteClassifier.classify(device.type)
    val name = try {
      device.productName?.toString().orEmpty()
    } catch (_: SecurityException) {
      ""
    }
    mainHandler.post { onRoute(kind, name) }
  }
}
