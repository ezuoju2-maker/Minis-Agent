package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ScheduledRunNow {

    private const val TAG = "SchedRunNow"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = HashSet<String>()

    fun isStarting(taskId: String): Boolean = synchronized(inFlight) { taskId in inFlight }

    fun canOffer(countdown: Boolean, nextFireAt: Long?, running: Boolean, starting: Boolean): Boolean =
        countdown && nextFireAt != null && !running && !starting

    private fun isRunning(taskId: String): Boolean =
        taskId in ScheduledSessionHold.runningTaskIds.value.keys

    fun start(
        context: Context,
        taskId: String,
        manager: ScheduledTaskManager = ScheduledTaskManager(context.applicationContext),
        expectedRunKey: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(inFlight) {
            if (isRunning(taskId) || !inFlight.add(taskId)) {
                AppLogger.info(TAG, "task=$taskId not started: already running or starting")
                return false
            }
        }
        val claimed = manager.claimRunNow(taskId, now, isRunning = ::isRunning, expectedRunKey = expectedRunKey)
        if (claimed == null) {
            synchronized(inFlight) { inFlight.remove(taskId) }
            AppLogger.info(TAG, "task=$taskId not started: no pending run to take")
            return false
        }
        ScheduledSessionHold.beginRun(taskId)
        synchronized(inFlight) { inFlight.remove(taskId) }
        val app = context.applicationContext
        scope.launch {
            try {
                ScheduledAgentRunner.run(app, claimed, waitForCompletion = false, scheduledFire = true)
            } catch (t: Throwable) {
                AppLogger.error(TAG, "task=$taskId run now failed: ${t.message}")
            } finally {
                ScheduledSessionHold.endRun(taskId)
            }
        }
        AppLogger.info(TAG, "task=$taskId started now")
        return true
    }
}
