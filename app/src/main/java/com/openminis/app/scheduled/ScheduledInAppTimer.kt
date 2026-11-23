package com.openminis.app.scheduled

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object ScheduledInAppTimer {

    private const val TAG = "SchedInAppTimer"

    private const val SLEEP_STEP_MS = 30_000L

    private val started = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            ScheduledTaskStore(app).observe().collectLatest { tasks -> fireAsDue(app, tasks) }
        }
    }

    private suspend fun fireAsDue(app: Context, tasks: List<ScheduledTask>) {
        val tried = HashSet<String>()
        while (true) {
            val due = nextDue(tasks, System.currentTimeMillis(), tried) ?: return
            AppLogger.info(TAG, "task=${due.task.id} ${due.runKey} waiting until ${due.at}")
            sleepUntil(due.at)
            tried += due.tryKey
            if (!canFire()) {
                AppLogger.info(TAG, "task=${due.task.id} due, Minis in background - left to its alarm")
                continue
            }
            val ok = withContext(Dispatchers.IO) {
                ScheduledRunNow.start(app, due.task.id, expectedRunKey = due.runKey, now = due.at - 1)
            }
            val lateMs = System.currentTimeMillis() - due.at
            AppLogger.info(TAG, "task=${due.task.id} ${due.runKey} fired=$ok late=${lateMs}ms")
        }
    }

    internal data class Due(val task: ScheduledTask, val at: Long, val runKey: String) {
        val tryKey: String get() = "${task.id}|$runKey"
    }

    internal fun nextDue(tasks: List<ScheduledTask>, now: Long, tried: Set<String>): Due? =
        tasks.asSequence()
            .filter { it.enabled && it.triggerKind != ScheduledTriggerKind.ON_COMPLETION }
            .mapNotNull { t ->
                val at = t.nextTriggerMs(now) ?: return@mapNotNull null
                val key = ScheduledTaskManager.pendingRunKey(t, now) ?: return@mapNotNull null
                Due(t, at, key).takeIf { it.tryKey !in tried }
            }
            .minByOrNull { it.at }

    private suspend fun sleepUntil(at: Long) {
        while (true) {
            val remaining = at - System.currentTimeMillis()
            if (remaining <= 0) return
            delay(minOf(remaining, SLEEP_STEP_MS))
        }
    }

    private fun canFire(): Boolean =
        AgentForegroundService.isAlive || runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)
}
