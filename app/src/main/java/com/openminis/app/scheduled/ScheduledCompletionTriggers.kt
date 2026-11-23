package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object ScheduledCompletionTriggers {

    private const val TAG = "ScheduledCompletion"

    const val RESULT_CAP_CHARS = 8_000

    private val listening = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensureListening(context: Context) {
        if (!listening.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            AgentJobRegistry.completions.collect { c ->
                val result = AgentJobRegistry.job(c.jobId)?.resultText
                runCatching { onUpstreamFinished(app, c.jobId, result) }
                    .onFailure { AppLogger.warning(TAG, "on-completion for job ${c.jobId.take(8)} failed: ${it.message}") }
            }
        }
    }

    internal fun dependents(tasks: List<ScheduledTask>, upstreamId: String): List<ScheduledTask> =
        tasks.filter {
            it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION && it.onCompletionOf == upstreamId
        }

    suspend fun onScheduledRunFinished(context: Context, taskId: String, result: String?) {
        val task = ScheduledTaskManager(context).get(taskId)
        if (!scheduleFinished(task, System.currentTimeMillis())) return
        onUpstreamFinished(context, taskId, result)
    }

    internal fun scheduleFinished(task: ScheduledTask?, now: Long): Boolean {
        if (task == null) return false
        return when (task.triggerKind) {
            ScheduledTriggerKind.AFTER, ScheduledTriggerKind.ON_COMPLETION -> true
            ScheduledTriggerKind.INTERVAL -> task.remainingFires == 0
            ScheduledTriggerKind.CALENDAR ->
                task.repeatMode == ScheduledRepeatMode.ONCE ||
                    (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)
        }
    }

    internal fun substitute(prompt: String, result: String?): String =
        prompt.replace(ScheduledTask.RESULT_PLACEHOLDER, (result ?: "").take(RESULT_CAP_CHARS))

    suspend fun onUpstreamFinished(context: Context, upstreamId: String, result: String?) {
        val manager = ScheduledTaskManager(context)
        val waiting = dependents(manager.list(), upstreamId)
        if (waiting.isEmpty()) return
        for (dep in waiting) {
            if (!manager.claimOneShotFire(dep.id)) continue
            AppLogger.info(TAG, "upstream ${upstreamId.take(8)} finished -> firing ${dep.id.take(8)} \"${dep.label}\"")
            ScheduledAgentRunner.run(
                context,
                dep.copy(prompt = substitute(dep.prompt, result)),
                waitForCompletion = false,
                scheduledFire = true,
            )
        }
    }
}
