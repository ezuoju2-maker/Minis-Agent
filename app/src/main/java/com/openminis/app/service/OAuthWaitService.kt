package com.openminis.app.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OAuthWaitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!goForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val n = OAuthWaitKeepAlive.prune()
        if (n == 0) {
            AppLogger.info(TAG, "[OAuthWait] service started after the flows ended - stopping at once")
            stopNow("no-active-flows")
            return START_NOT_STICKY
        }
        AppLogger.info(TAG, "[OAuthWait] service foreground active=$n startId=$startId")
        if (!observing) {
            observing = true
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            OAuthWaitKeepAlive.active.collectLatest { n ->
                if (n == 0) {
                    stopNow("all-flows-ended")
                    return@collectLatest
                }
                val next = OAuthWaitKeepAlive.nextDeadline() ?: return@collectLatest
                delay((next - OAuthWaitKeepAlive.now()).coerceAtLeast(0L) + 1)
                OAuthWaitKeepAlive.prune()
            }
        }
    }

    private fun goForeground(): Boolean {
        ensureChannel(this)
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "[OAuthWait] startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun stopNow(reason: String) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val stopped = stopSelfResult(lastStartId)
        AppLogger.info(TAG, "[OAuthWait] service stop reason=$reason stopped=$stopped")
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.oauth_wait_notif_title))
            .setContentText(getString(R.string.oauth_wait_notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        OAuthWaitKeepAlive.clear("task-removed")
        stopNow("task-removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "[OAuthWait] dataSync time limit reached - stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on API 35")
    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onDestroy() {
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OAuthWait"
        const val CHANNEL_ID = "oauth_wait"
        private const val NOTIFICATION_ID = 4301

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, OAuthWaitService::class.java))
            } catch (e: Exception) {
                val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) "ForegroundServiceStartNotAllowedException" else e.javaClass.simpleName
                AppLogger.error(TAG, "[OAuthWait] could not start service ($kind): ${e.message} - sign-in continues without it")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.oauth_wait_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
