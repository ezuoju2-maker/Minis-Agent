package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BackgroundTaskNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    fun notifyTaskCompleted(sessionId: String, outcome: BackgroundTurnOutcome = BackgroundTurnOutcome.COMPLETED) {
        if (!backgroundSettings.taskNotificationsEnabled.value) return
        if (isAppForeground()) return

        scope.launch {
            try {
                val session = chatRepository.getSession(sessionId)
                if (session?.isChild == true) {
                    AppLogger.info(
                        TAG,
                        "skipping completion notification for sub agent session " +
                            sessionId.take(8),
                    )
                    return@launch
                }
                val rawTitle = session?.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.notif_task_completed_default_title)
                val (title, body) = when (outcome) {
                    BackgroundTurnOutcome.FAILED ->
                        "❌ $rawTitle" to context.getString(R.string.notif_task_failed_body)
                    BackgroundTurnOutcome.INTERRUPTED ->
                        "⏸ $rawTitle" to context.getString(R.string.notif_task_paused_body)
                    BackgroundTurnOutcome.COMPLETED ->
                        rawTitle to context.getString(R.string.notif_task_completed_body)
                }
                val taskId = ScheduledRunNotification.taskIdOpeningTurn { offset ->
                    chatRepository.dao.messageHeadNewestFirst(sessionId, offset)
                }
                postNotification(sessionId, title, body, ScheduledRunNotification.tag(taskId))
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyTaskCompleted failed: ${t.message}")
            }
        }
    }

    private fun postNotification(sessionId: String, title: String, body: String, tag: String? = null) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        val deepLink = Uri.parse("minis://session/$sessionId")
        val launchIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            nm.notify(tag, sessionId.hashCode(), notification)
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    fun cancelAllCompletedNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        try {
            val active = nm.activeNotifications ?: return
            for (sb in active) {
                if (sb.notification?.channelId == CHANNEL_ID) {
                    nm.cancel(sb.tag, sb.id)
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "cancelAllCompletedNotifications failed: ${t.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_task_completed_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_task_completed_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TaskNotifier"
        const val CHANNEL_ID = "minis_task_completed"
    }
}
