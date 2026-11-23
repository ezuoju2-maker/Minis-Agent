package com.openminis.app.offload

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MinisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
    }

    override fun onListenerDisconnected() {
        instance = null
        connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val key = postKey(sbn.packageName, sbn.id)
        postedLatches.remove(key)?.countDown()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    companion object {
        private const val TAG = "NotifListener"

        @Volatile private var connected: Boolean = false
        @Volatile private var instance: MinisNotificationListenerService? = null

        private val postedLatches = ConcurrentHashMap<String, CountDownLatch>()

        private fun postKey(pkg: String, id: Int): String = "$pkg#$id"

        fun isConnected(): Boolean = connected

        fun awaitPosted(pkg: String, id: Int, timeoutMs: Long = 2_000L): Boolean {
            val svc = instance ?: run {
                AppLogger.warning(TAG, "awaitPosted($pkg#$id) called but listener not connected")
                return false
            }
            try {
                if (svc.activeNotifications.any { it.packageName == pkg && it.id == id }) {
                    return true
                }
            } catch (_: SecurityException) {
            }
            val key = postKey(pkg, id)
            val latch = postedLatches.computeIfAbsent(key) { CountDownLatch(1) }
            try {
                if (svc.activeNotifications.any { it.packageName == pkg && it.id == id }) {
                    postedLatches.remove(key)
                    return true
                }
            } catch (_: SecurityException) {}
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            postedLatches.remove(key)
            if (!ok) AppLogger.warning(TAG, "awaitPosted($key) timed out after ${timeoutMs}ms")
            return ok
        }

        fun getActiveNotifications(): Array<StatusBarNotification>? {
            return try {
                instance?.activeNotifications
            } catch (_: SecurityException) {
                null
            }
        }

        fun isEnabled(context: Context): Boolean {
            val pkg = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return flat.split(':').any { entry ->
                val cn = ComponentName.unflattenFromString(entry) ?: return@any false
                cn.packageName == pkg
            }
        }

        const val SETTINGS_ACTION: String = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
}
