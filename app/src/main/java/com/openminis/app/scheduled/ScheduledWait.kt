package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.BackgroundSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class ScheduledWait(
    val sessionId: String,
    val taskId: String,
    val label: String,
    val nextRunMs: Long?,
    val runCounter: String?,
    val chatTitle: String? = null,
)

data class ScheduledFiring(
    val sessionId: String,
    val taskId: String,
    val runCounter: String,
)

object ScheduledWaitModel {

    fun nextRunNumber(task: ScheduledTask): Int? = when {
        task.triggerKind == ScheduledTriggerKind.INTERVAL -> {
            val n = (task.triggeredCount ?: 0) + 1
            task.maxFires?.let { minOf(n, it) } ?: n
        }
        task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE -> task.firesSoFar + 1
        else -> null
    }

    fun runningRunNumber(task: ScheduledTask): Int? = when {
        task.triggerKind == ScheduledTriggerKind.INTERVAL -> {
            val n = maxOf(1, task.triggeredCount ?: 0)
            task.maxFires?.let { minOf(n, it) } ?: n
        }
        task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE -> task.firesSoFar + 1
        else -> null
    }

    fun runningCounter(task: ScheduledTask): String? {
        val n = runningRunNumber(task) ?: return null
        val total = task.maxFires.takeIf { task.triggerKind == ScheduledTriggerKind.INTERVAL }
        return if (total != null) "$n/$total" else "#$n"
    }

    fun firing(tasks: List<ScheduledTask>, runningIds: Set<String>): ScheduledFiring? =
        tasks.asSequence()
            .filter { it.id in runningIds }
            .mapNotNull { t ->
                val sid = ScheduledSessionHold.runHold(t) ?: return@mapNotNull null
                val counter = runningCounter(t) ?: return@mapNotNull null
                ScheduledFiring(sid, t.id, counter)
            }
            .minByOrNull { it.taskId }

    fun observeFiring(context: Context): Flow<ScheduledFiring?> =
        combine(
            ScheduledTaskStore(context.applicationContext).observe(),
            ScheduledSessionHold.runningTaskIds,
        ) { tasks, running -> firing(tasks, running.keys) }.distinctUntilChanged()

    fun runCounter(task: ScheduledTask): String? {
        val n = nextRunNumber(task) ?: return null
        val total = task.maxFires.takeIf { task.triggerKind == ScheduledTriggerKind.INTERVAL }
        return if (total != null) "$n/$total" else "#$n"
    }

    fun pick(
        tasks: List<ScheduledTask>,
        now: Long,
        leadMs: Long = Long.MAX_VALUE,
        upstreamUnfinished: (String) -> Boolean,
    ): ScheduledWait? {
        val held = tasks.mapNotNull { t ->
            ScheduledSessionHold.heldSession(t, now, upstreamUnfinished, leadMs)?.let { sid -> t to sid }
        }
        if (held.isEmpty()) return null
        val timed = held.mapNotNull { (t, sid) -> t.nextTriggerMs(now)?.let { Triple(t, sid, it) } }
            .minWithOrNull(compareBy<Triple<ScheduledTask, String, Long>> { it.third }.thenBy { it.first.id })
        if (timed != null) {
            val (t, sid, next) = timed
            return ScheduledWait(sid, t.id, t.label, next, runCounter(t))
        }
        val (t, sid) = held.minWith(compareBy<Pair<ScheduledTask, String>> { it.first.label }.thenBy { it.first.id })
        return ScheduledWait(sid, t.id, t.label, null, runCounter(t))
    }

    fun observe(context: Context): Flow<ScheduledWait?> =
        ScheduledSessionHold.windowed(
            combine(
                ScheduledTaskStore(context.applicationContext).observe(),
                ScheduledSessionHold.runningTaskIds,
                AgentJobRegistry.jobs,
                BackgroundSettingsRepository(context.applicationContext).scheduledCountdownLead,
            ) { tasks, running, jobs, lead ->
                ScheduledSessionHold.Inputs(tasks, running.keys, jobs, BackgroundSettingsRepository.scheduledCountdownLeadMs(lead))
            },
            tasksOf = { it.tasks },
            leadOf = { it.leadMs },
        ) { i, now ->
            pick(i.tasks, now, i.leadMs) { ScheduledSessionHold.upstreamUnfinished(it, i.tasks, i.runningIds, i.jobs, now) }
        }.distinctUntilChanged()
}
