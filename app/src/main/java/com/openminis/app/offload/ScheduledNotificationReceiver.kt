package com.openminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

class ScheduledNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "scheduled_notification_id"
        const val EXTRA_TITLE = "scheduled_notification_title"
        const val EXTRA_BODY = "scheduled_notification_body"
        const val CHANNEL_ID = "minis_agent_notifications"
        private const val TAG = "ScheduledNotifReceiver"

        fun contentIntentFor(context: Context, notifId: Int): PendingIntent {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                notifId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: "unknown"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Minis"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        AppLogger.debug(TAG, "scheduled notification fired: id=$id title='$title'")

        try {
            ScheduledNotificationStore(context).remove(id)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "remove($id) failed: ${e.message}")
        }

        val notifId = id.hashCode() and 0x7FFFFFFF
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntentFor(context, notifId))
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "post denied — POST_NOTIFICATIONS missing: ${e.message}")
        }
    }
}
