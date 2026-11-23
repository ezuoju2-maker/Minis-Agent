package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.logging.AppLogger

class ConfigConfirmNotifier(
    private val context: Context,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    init {
        ensureChannel()
    }

    fun notifyIfBackgrounded(change: PendingConfigChange): Boolean {
        if (isAppForeground()) return false
        if (!backgroundSettings.taskNotificationsEnabled.value) {
            AppLogger.info(TAG, "bg-notify skipped id=${change.id} — taskNotificationsEnabled=false")
            return false
        }

        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return false
        }

        val caption = change.caption?.trim().orEmpty()
        val body = when {
            caption.isNotEmpty() -> caption
            change.items.size > 1 ->
                context.getString(R.string.notif_config_confirm_body_multi, change.items.size)
            else -> context.getString(R.string.notif_config_confirm_body_single)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            change.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_config_confirm_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        return try {
            nm.notify(NOTIFICATION_TAG, change.id.hashCode(), notification)
            AppLogger.info(TAG, "bg-notify post id=${change.id} bodyLen=${body.length}")
            true
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
            false
        }
    }

    fun cancel(changeId: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, changeId.hashCode())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_config_confirm_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_config_confirm_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ConfigConfirmNotifier"
        const val CHANNEL_ID = "minis_config_confirm"
        private const val NOTIFICATION_TAG = "config-confirm"
    }
}
