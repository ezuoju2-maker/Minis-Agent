package com.openminis.app.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ScheduledTaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledTaskManager.ACTION_FIRE) return
        val taskId = intent.getStringExtra(ScheduledTaskManager.EXTRA_TASK_ID) ?: return

        AppLogger.info(TAG, "fire received: task=$taskId")

        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val startedAt = SystemClock.elapsedRealtime()
        scope.launch {
            ScheduledSessionHold.beginRun(taskId)
            try {
                withTimeout(HANDOFF_TIMEOUT_MS) {
                    val manager = ScheduledTaskManager(appContext)
                    val task = manager.get(taskId) ?: run {
                        AppLogger.warning(TAG, "task $taskId not found in store — skipping")
                        return@withTimeout
                    }
                    if (!task.enabled) {
                        AppLogger.info(TAG, "task $taskId disabled — skipping fire")
                        return@withTimeout
                    }
                    val slot = intent.getLongExtra(ScheduledTaskManager.EXTRA_TRIGGER_AT, -1L).takeIf { it > 0 }
                    if (!manager.rescheduleNext(taskId, slot)) return@withTimeout
                    val current = manager.get(taskId) ?: task

                    ScheduledAgentRunner.run(appContext, current, waitForCompletion = false, scheduledFire = true)
                }
            } catch (t: Throwable) {
                AppLogger.error(TAG, "task $taskId fire failed: ${t.message}")
            } finally {
                ScheduledSessionHold.endRun(taskId)
                pending.finish()
                AppLogger.info(
                    TAG,
                    "task $taskId handoff done in ${SystemClock.elapsedRealtime() - startedAt}ms",
                )
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskRecv"

        private const val HANDOFF_TIMEOUT_MS = 8_000L
    }
}
