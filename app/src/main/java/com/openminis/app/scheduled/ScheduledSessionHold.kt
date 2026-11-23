package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.BackgroundSettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

object ScheduledSessionHold {

    fun heldSession(
        task: ScheduledTask,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
        leadMs: Long = Long.MAX_VALUE,
    ): String? {
        if (!task.enabled) return null
        if (leadMs <= 0L) return null
        val target = task.targetMode as? ScheduledTargetMode.AppendToSession ?: return null
        val creator = task.creatorSessionId ?: return null
        if (target.sessionId != creator) return null
        when (task.triggerKind) {
            ScheduledTriggerKind.ON_COMPLETION -> {
                val upstream = task.onCompletionOf ?: return null
                if (!upstreamUnfinished(upstream)) return null
            }
            else -> {
                val next = task.nextTriggerMs(now) ?: return null
                if (next - now > leadMs) return null
            }
        }
        return creator
    }

    fun nextWindowStart(tasks: List<ScheduledTask>, now: Long, leadMs: Long): Long? {
        if (leadMs <= 0L || leadMs == Long.MAX_VALUE) return null
        return tasks.asSequence()
            .filter { it.enabled && it.triggerKind != ScheduledTriggerKind.ON_COMPLETION && runHold(it) != null }
            .mapNotNull { it.nextTriggerMs(now) }
            .map { it - leadMs }
            .filter { it > now }
            .minOrNull()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun <I, T> windowed(
        inputs: Flow<I>,
        tasksOf: (I) -> List<ScheduledTask>,
        leadOf: (I) -> Long,
        derive: (I, Long) -> T,
    ): Flow<T> = inputs.flatMapLatest { i ->
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(derive(i, now))
                val next = nextWindowStart(tasksOf(i), now, leadOf(i)) ?: break
                delay((next - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
            }
        }
    }

    fun isLive(task: ScheduledTask, now: Long): Boolean =
        task.enabled && (task.triggerKind == ScheduledTriggerKind.ON_COMPLETION || task.nextTriggerMs(now) != null)

    fun heldSessions(
        tasks: List<ScheduledTask>,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
        leadMs: Long = Long.MAX_VALUE,
    ): Set<String> =
        tasks.mapNotNullTo(LinkedHashSet()) { heldSession(it, now, upstreamUnfinished, leadMs) }

    fun upstreamUnfinished(
        id: String,
        tasks: List<ScheduledTask>,
        runningTaskIds: Set<String>,
        jobs: Map<String, AgentJob>,
        now: Long,
    ): Boolean {
        if (id in runningTaskIds) return true
        tasks.firstOrNull { it.id == id }?.let { t ->
            if (!t.enabled) return false
            return t.triggerKind == ScheduledTriggerKind.ON_COMPLETION || t.nextTriggerMs(now) != null
        }
        return jobs[id]?.isActive == true
    }

    private val _runningRuns = MutableStateFlow<Map<String, Int>>(emptyMap())
    val runningTaskIds: StateFlow<Map<String, Int>> = _runningRuns.asStateFlow()

    fun beginRun(taskId: String) {
        _runningRuns.update { it + (taskId to ((it[taskId] ?: 0) + 1)) }
    }

    fun endRun(taskId: String) {
        _runningRuns.update { m ->
            val n = (m[taskId] ?: 0) - 1
            if (n > 0) m + (taskId to n) else m - taskId
        }
    }

    fun observe(context: Context): Flow<Set<String>> =
        windowed(
            combine(
                ScheduledTaskStore(context.applicationContext).observe(),
                _runningRuns,
                AgentJobRegistry.jobs,
                BackgroundSettingsRepository(context.applicationContext).scheduledCountdownLead,
            ) { tasks, running, jobs, lead ->
                Inputs(tasks, running.keys, jobs, BackgroundSettingsRepository.scheduledCountdownLeadMs(lead))
            },
            tasksOf = { it.tasks },
            leadOf = { it.leadMs },
        ) { i, now -> heldNow(i.tasks, i.runningIds, i.jobs, now, i.leadMs) }.distinctUntilChanged()

    internal data class Inputs(
        val tasks: List<ScheduledTask>,
        val runningIds: Set<String>,
        val jobs: Map<String, AgentJob>,
        val leadMs: Long,
    )

    fun heldNow(
        tasks: List<ScheduledTask>,
        runningIds: Set<String>,
        jobs: Map<String, AgentJob>,
        now: Long,
        leadMs: Long = Long.MAX_VALUE,
    ): Set<String> =
        heldSessions(tasks, now, { upstreamUnfinished(it, tasks, runningIds, jobs, now) }, leadMs) +
            tasks.filter { it.id in runningIds }.mapNotNull(::runHold)

    fun runHold(task: ScheduledTask): String? {
        val target = task.targetMode as? ScheduledTargetMode.AppendToSession ?: return null
        return target.sessionId.takeIf { it == task.creatorSessionId }
    }
}
