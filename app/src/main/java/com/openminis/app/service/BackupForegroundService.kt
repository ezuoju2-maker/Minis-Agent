package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.backup.BackupRunController
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class BackupForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var observing = false
    private var lastPostedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogger.info(TAG, "[BackupFGS] stop requested from notification")
            BackupRunController.stop()
            return START_NOT_STICKY
        }
        goForeground(BackupRunController.statusText.value)
        acquireLocks()
        if (!observing) {
            observing = true
            observeRun()
        }
        return START_NOT_STICKY
    }

    private fun observeRun() {
        scope.launch {
            combine(BackupRunController.isRunning, BackupRunController.statusText) { running, status ->
                running to status
            }.distinctUntilChanged().collect { (running, status) ->
                if (running) {
                    updateProgress(status)
                } else {
                    finish()
                }
            }
        }
    }

    private fun goForeground(status: String?) {
        ensureChannel(this)
        val n = progressNotification(status)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_ID, n)
            }
        }.onFailure {
            AppLogger.error(TAG, "[BackupFGS] startForeground refused: ${it.message}")
            stopSelf()
        }
    }

    private fun updateProgress(status: String?) {
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < PROGRESS_THROTTLE_MS) return
        lastPostedAt = now
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(PROGRESS_ID, progressNotification(status))
        }
    }

    private fun finish() {
        postOutcome()
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    private fun postOutcome() {
        val error = BackupRunController.errorText.value
        val (title, text) = when (outcomeOf(BackupRunController.lastResult.value, error)) {
            Outcome.LOCAL_PENDING ->
                getString(R.string.backup_notif_done_title) to getString(R.string.backup_notif_local_pending)
            Outcome.DELIVERED ->
                getString(R.string.backup_notif_done_title) to getString(R.string.backup_notif_done_text)
            Outcome.ISSUES ->
                getString(R.string.backup_notif_issues_title) to (error ?: "")
            Outcome.FAILED ->
                getString(R.string.backup_notif_failed_title) to (error ?: "")
            Outcome.STOPPED ->
                getString(R.string.backup_notif_stopped_title) to getString(R.string.backup_notif_stopped_text)
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_completed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(OUTCOME_ID, n) }
    }

    private fun progressNotification(status: String?): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BackupForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.backup_notif_running_title))
            .setContentText(status ?: getString(R.string.backup_notif_running_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.backup_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Minis:Backup")
                    .apply {
                        setReferenceCounted(false)
                        acquire(WAKE_LOCK_CAP_MS)
                    }
            }.onFailure { AppLogger.error(TAG, "[BackupFGS] wake lock failed: ${it.message}") }
                .getOrNull()
        }
        if (wifiLock == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Minis:Backup")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "[BackupFGS] dataSync time limit reached — releasing keep-alive")
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on API 35")
    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    internal enum class Outcome { LOCAL_PENDING, DELIVERED, ISSUES, FAILED, STOPPED }

    companion object {
        private const val TAG = "BackupForegroundService"

        internal fun outcomeOf(result: BackupRunController.RunResult?, error: String?): Outcome = when {
            result != null && result.localOnly -> Outcome.LOCAL_PENDING
            result != null && error == null -> Outcome.DELIVERED
            result != null -> Outcome.ISSUES
            error != null -> Outcome.FAILED
            else -> Outcome.STOPPED
        }
        const val CHANNEL_ID = "backup"
        private const val PROGRESS_ID = 4201
        private const val OUTCOME_ID = 4202
        private const val ACTION_STOP = "com.openminis.app.backup.STOP"
        internal const val PROGRESS_THROTTLE_MS = 1_000L
        internal const val WAKE_LOCK_CAP_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching {
                val i = Intent(context, BackupForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure {
                AppLogger.error(TAG, "[BackupFGS] could not start: ${it.message}")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.backup_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
