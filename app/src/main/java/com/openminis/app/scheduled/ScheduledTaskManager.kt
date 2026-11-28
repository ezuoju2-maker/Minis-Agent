package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger

class ScheduledTaskManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = ScheduledTaskStore(context)

    init { removeObsoleteNotificationChannel() }

    fun store(): ScheduledTaskStore = store

    fun list(): List<ScheduledTask> = store.all()
    fun get(taskId: String): ScheduledTask? = store.get(taskId)

    fun create(task: ScheduledTask): ScheduledTask {
        val armed = if (task.usesAnchor && task.anchorMs == null) {
            task.copy(anchorMs = System.currentTimeMillis())
        } else task
        store.upsert(armed)
        if (armed.enabled) registerAlarm(armed)
        if (armed.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        return armed
    }

    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        val merged = store.get(task.id)?.let { prev -> mergeBookkeeping(task, prev) } ?: task
        store.upsert(merged)
        if (merged.enabled) registerAlarm(merged)
        return merged
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        val t = store.get(taskId) ?: return
        val updated = if (enabled && t.usesAnchor) {
            t.copy(enabled = true, anchorMs = System.currentTimeMillis(), triggeredCount = 0)
        } else if (enabled && t.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            t.copy(enabled = true, triggeredCount = 0)
        } else {
            t.copy(enabled = enabled)
        }
        store.upsert(updated)
        if (enabled) registerAlarm(updated) else cancelAlarm(taskId)
    }

    fun claimOneShotFire(taskId: String): Boolean =
        store.update(taskId) { t ->
            if (!t.enabled || (t.triggeredCount ?: 0) >= 1) null
            else t.copy(enabled = false, triggeredCount = 1)
        } != null

    fun delete(taskId: String) {
        cancelAlarm(taskId)
        store.delete(taskId)
    }

    fun disableAllCreatedBy(sessionId: String, reason: String): Int {
        val live = createdByEnabled(store.all(), sessionId)
        var n = 0
        for (t in live) {
            val off = store.update(t.id) { cur -> if (cur.enabled) cur.copy(enabled = false) else null }
            if (off != null) {
                cancelAlarm(t.id)
                n++
            }
        }
        if (n > 0) AppLogger.info(TAG, "disabled $n scheduled task(s) created by ${sessionId.take(8)}: $reason")
        return n
    }

    fun rescheduleAll() {
        val tasks = store.all()
        if (tasks.isEmpty()) {
            AppLogger.info(TAG, "rescheduleAll: no tasks")
            return
        }
        var count = 0
        if (tasks.any { it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION }) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        for (t in tasks) {
            if (!t.enabled) continue
            registerAlarm(t)
            count++
        }
        AppLogger.info(TAG, "rescheduleAll: re-registered $count enabled task(s)")
    }

    fun rescheduleNext(taskId: String, firedSlotMs: Long? = null): Boolean {
        val t = store.get(taskId) ?: return false
        if (!t.isCalendar) {
            val now = System.currentTimeMillis()
            var arm = false
            val stored = store.update(taskId) { cur ->
                if (!cur.enabled || !relativeAlarmDue(cur, now)) return@update null
                val (next, a) = afterAlarmFire(cur, now) ?: return@update null
                arm = a
                next
            }
            if (stored == null) {
                val cur = store.get(taskId)
                AppLogger.info(TAG, "alarm for task=$taskId is not a due fire (enabled=${cur?.enabled}) — not running")
                if (cur != null && cur.enabled) registerAlarm(cur)
                return false
            }
            if (arm) registerAlarm(stored)
            return true
        }
        val now = System.currentTimeMillis()
        val stored = store.update(taskId) { cur ->
            if (!cur.enabled || calendarSlotTakenByRunNow(cur, now)) return@update null
            if (cur.repeatMode == ScheduledRepeatMode.ONCE) {
                cur.copy(enabled = false)
            } else {
                firedSlotMs?.let { cur.copy(runNowSlotMs = it) } ?: cur
            }
        }
        if (stored == null) {
            val cur = store.get(taskId)
            AppLogger.info(TAG, "alarm for task=$taskId: slot already run by Run now (enabled=${cur?.enabled}) — not running")
            if (cur != null && cur.enabled) registerAlarm(cur)
            return false
        }
        if (stored.enabled) registerAlarm(stored)
        return true
    }

    fun claimRunNow(
        taskId: String,
        now: Long = System.currentTimeMillis(),
        isRunning: (String) -> Boolean = { false },
        expectedRunKey: String? = null,
    ): ScheduledTask? {
        val stored = store.update(taskId) { cur ->
            if (isRunning(cur.id)) return@update null
            if (expectedRunKey != null && pendingRunKey(cur, now) != expectedRunKey) return@update null
            runNowTransition(cur, now)
        } ?: return null
        if (stored.enabled && stored.nextTriggerMs(now) != null) registerAlarm(stored) else cancelAlarm(taskId)
        AppLogger.info(
            TAG,
            "run now task=$taskId kind=${stored.triggerKind} count=${stored.triggeredCount} " +
                "slot=${stored.runNowSlotMs} enabled=${stored.enabled} next=${stored.nextTriggerMs(now)}",
        )
        return stored
    }

    fun markFired(
        taskId: String,
        sessionId: String?,
        resultPreview: String?,
        ok: Boolean = true,
        firedAt: Long = System.currentTimeMillis(),
    ) {
        val now = firedAt
        val run = ScheduledRun(firedAt = now, sessionId = sessionId, preview = resultPreview, ok = ok)
        store.update(taskId) { t ->
            t.copy(
                lastFiredAt = now,
                lastResultPreview = resultPreview,
                lastResultSessionId = sessionId,
                runHistory = (listOf(run) + t.runHistory).take(ScheduledTask.MAX_RUN_HISTORY),
                fireCount = t.firesSoFar + 1,
            )
        }
    }

    private fun registerAlarm(task: ScheduledTask) {
        val triggerAt = task.nextTriggerMs() ?: run {
            AppLogger.warning(TAG, "task ${task.id} has no next trigger — skipping register")
            return
        }
        val pi = buildPendingIntent(task.id, triggerAt)
        val exactAllowed = if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        if (!exactAllowed) {
            AppLogger.warning(
                TAG,
                "exact alarms are not granted; scheduling task=${task.id} inexactly " +
                    "(the user can allow exact alarms in system settings)",
            )
        }
        try {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                return
            }
            AppLogger.info(TAG, "registered task=${task.id} label=\"${task.label}\" triggerAt=$triggerAt")
        } catch (e: SecurityException) {
            AppLogger.warning(
                TAG,
                "exact-alarm denied for task=${task.id} (${e.message}); falling back to inexact",
            )
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for task=${task.id}: ${t2.message}")
            }
        }
    }

    private fun cancelAlarm(taskId: String) {
        val pi = buildPendingIntent(taskId)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(taskId: String, triggerAt: Long? = null): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
            if (triggerAt != null) putExtra(EXTRA_TRIGGER_AT, triggerAt)
        }
        val requestCode = taskId.hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun removeObsoleteNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!obsoleteChannelChecked.compareAndSet(false, true)) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(OBSOLETE_CHANNEL_ID) != null) {
                nm.deleteNotificationChannel(OBSOLETE_CHANNEL_ID)
                AppLogger.info(TAG, "deleted obsolete notification channel $OBSOLETE_CHANNEL_ID")
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskManager"

        private const val ALARM_EARLY_SLACK_MS = 5_000L

        internal fun relativeAlarmDue(t: ScheduledTask, now: Long): Boolean {
            val anchor = t.anchorMs ?: t.createdAt
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER ->
                    (t.triggeredCount ?: 0) < 1 && anchor + (t.delaySec ?: 0L) * 1000 <= now + ALARM_EARLY_SLACK_MS
                ScheduledTriggerKind.INTERVAL -> {
                    val iv = t.intervalSec ?: return false
                    (t.maxFires == null || (t.triggeredCount ?: 0) < t.maxFires) &&
                        anchor + iv * 1000 <= now + ALARM_EARLY_SLACK_MS
                }
                ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> true
            }
        }

        internal fun afterAlarmFire(t: ScheduledTask, now: Long): Pair<ScheduledTask, Boolean>? = when (t.triggerKind) {
            ScheduledTriggerKind.AFTER -> t.copy(enabled = false, triggeredCount = 1) to false
            ScheduledTriggerKind.INTERVAL -> {
                val n = (t.triggeredCount ?: 0) + 1
                val next = t.copy(triggeredCount = n, anchorMs = now)
                if (t.maxFires != null && n >= t.maxFires) next.copy(enabled = false) to false else next to true
            }
            ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> null
        }

        internal fun calendarSlotTakenByRunNow(t: ScheduledTask, now: Long): Boolean {
            val slot = t.runNowSlotMs ?: return false
            return now >= slot - ALARM_EARLY_SLACK_MS && now <= slot + RUN_NOW_LATE_WINDOW_MS
        }

        internal fun pendingRunKey(t: ScheduledTask, now: Long): String? {
            if (!t.enabled) return null
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER, ScheduledTriggerKind.INTERVAL ->
                    if (t.nextTriggerMs(now) == null) null else "run:${t.triggeredCount ?: 0}"
                ScheduledTriggerKind.CALENDAR -> t.nextTriggerMs(now)?.let { "slot:$it" }
                ScheduledTriggerKind.ON_COMPLETION -> null
            }
        }

        private const val RUN_NOW_LATE_WINDOW_MS = 30 * 60_000L

        internal fun runNowTransition(t: ScheduledTask, now: Long): ScheduledTask? {
            if (!t.enabled) return null
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER, ScheduledTriggerKind.INTERVAL -> {
                    if (t.nextTriggerMs(now) == null) null else afterAlarmFire(t, now)?.first
                }
                ScheduledTriggerKind.CALENDAR -> {
                    val slot = t.nextTriggerMs(now) ?: return null
                    t.copy(runNowSlotMs = slot, enabled = t.repeatMode != ScheduledRepeatMode.ONCE)
                }
                ScheduledTriggerKind.ON_COMPLETION -> null
            }
        }

        internal fun createdByEnabled(tasks: List<ScheduledTask>, sessionId: String): List<ScheduledTask> =
            tasks.filter { it.enabled && it.creatorSessionId == sessionId }

        internal fun mergeBookkeeping(incoming: ScheduledTask, prev: ScheduledTask): ScheduledTask =
            incoming.copy(
                runHistory = incoming.runHistory.ifEmpty { prev.runHistory },
                fireCount = incoming.fireCount ?: prev.fireCount,
                anchorMs = incoming.anchorMs ?: prev.anchorMs,
                triggeredCount = incoming.triggeredCount ?: prev.triggeredCount,
                creatorSessionId = incoming.creatorSessionId ?: prev.creatorSessionId,
            )
        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val OBSOLETE_CHANNEL_ID = "minis_scheduled_tasks"
        private val obsoleteChannelChecked = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
