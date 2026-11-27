package expo.modules.karaokeaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the mic passthrough alive when StageMic is in the background or the
 * screen is locked. The notification is the way to mute or stop without
 * opening the app. START_NOT_STICKY means a killed process does not turn
 * the microphone back on by itself.
 */
class KaraokeForegroundService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    KaraokeRuntime.onMuteChanged = { updateNotification() }
    KaraokeRuntime.onRequestStop = {
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startInForeground()
    when (intent?.action) {
      ACTION_STOP -> {
        KaraokeRuntime.stopEngine()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
      ACTION_TOGGLE_MUTE -> {
        KaraokeRuntime.toggleMute()
        updateNotification()
      }
      else -> {
        if (!KaraokeRuntime.isRunning()) {
          try {
            KaraokeRuntime.startEngine(applicationContext)
          } catch (_: Throwable) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
          }
        }
        acquireWakeLock()
        updateNotification()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    KaraokeRuntime.onMuteChanged = null
    KaraokeRuntime.onRequestStop = null
    KaraokeRuntime.stopEngine()
    releaseWakeLock()
    super.onDestroy()
  }

  private fun startInForeground() {
    val playback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    } else {
      0
    }
    val microphone = if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      KaraokeRuntime.capturesMicrophone()
    ) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    } else {
      0
    }
    val type = microphone or playback
    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
  }

  private fun updateNotification() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    manager.notify(NOTIFICATION_ID, buildNotification())
  }

  private fun buildNotification(): Notification {
    val muted = KaraokeRuntime.muted
    val muteAction = NotificationCompat.Action.Builder(
      0,
      if (muted) "Unmute" else "Mute",
      servicePendingIntent(ACTION_TOGGLE_MUTE, 11),
    ).build()
    val stopAction = NotificationCompat.Action.Builder(
      0,
      "Stop",
      servicePendingIntent(ACTION_STOP, 12),
    ).build()
    val text = when {
      muted -> "Muted. The mic stays open until you stop. Music apps keep playing."
      KaraokeRuntime.mode == "wifi" && KaraokeRuntime.wifiRole == "sender" ->
        "Handheld mic is streaming over Wi-Fi. Music apps keep playing."
      KaraokeRuntime.mode == "wifi" && KaraokeRuntime.wifiRole == "receiver" ->
        "Speaker receiver is live. Music apps keep playing."
      else -> "Mic is live on the speaker. Music apps keep playing."
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_mic)
      .setContentTitle("StageMic")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(Notification.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setContentIntent(contentIntent())
      .setColor(0xFFE2A52B.toInt())
      .addAction(muteAction)
      .addAction(stopAction)
      .build()
  }

  private fun contentIntent(): PendingIntent? {
    val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    return PendingIntent.getActivity(
      this,
      13,
      launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
    val intent = Intent(this, KaraokeForegroundService::class.java).setAction(action)
    return PendingIntent.getService(
      this,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val manager = getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "StageMic",
      NotificationManager.IMPORTANCE_LOW,
    )
    channel.description = "Shows while your voice is sent to the speaker."
    channel.setSound(null, null)
    manager.createNotificationChannel(channel)
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) {
      return
    }
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stagemic:mic")
    lock.setReferenceCounted(false)
    lock.acquire(8 * 60 * 60 * 1000L)
    wakeLock = lock
  }

  private fun releaseWakeLock() {
    val lock = wakeLock ?: return
    if (lock.isHeld) {
      lock.release()
    }
    wakeLock = null
  }

  companion object {
    const val CHANNEL_ID = "stagemic.mic"
    const val NOTIFICATION_ID = 4816
    const val ACTION_TOGGLE_MUTE = "expo.modules.karaokeaudio.TOGGLE_MUTE"
    const val ACTION_STOP = "expo.modules.karaokeaudio.STOP"
  }
}
