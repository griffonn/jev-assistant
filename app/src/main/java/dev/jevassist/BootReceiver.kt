package dev.jevassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android doesn't let apps start background microphone listening right after boot,
 * so after a restart we post a notification; one tap turns "Hey Jev" back on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPrefs(context).wakeEnabled) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(WakeWordService.CHANNEL, "Hey Jev listening", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_RESTART_WAKE, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            3,
            Notification.Builder(context, WakeWordService.CHANNEL)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentTitle("Tap to turn “Hey Jev” back on")
                .setContentText("Android pauses background listening after a restart.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
