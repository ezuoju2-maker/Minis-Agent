package com.openminis.app.ui.chat

import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledSessionHold
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind

internal data class PillJob(
    val id: String,
    val title: String,
    val nextFireAt: Long?,
    val windowStart: Long,
    val remaining: Int?,
    val isFiringNow: Boolean,
    val runsDone: Int,
    val runTotal: Int?,
    val repeats: Boolean,
)

internal object ScheduledPillModel {
    enum class Phase {
        COUNTDOWN,
        RUNNING,
        WAITING,
    }

    data class State(val job: PillJob, val phase: Phase,  val others: Int)

    fun state(jobs: List<PillJob>): State? {
        if (jobs.isEmpty()) return null
        val others = jobs.size - 1
        val soonest = compareBy<PillJob>({ it.nextFireAt ?: Long.MAX_VALUE }, { it.id })
        jobs.filter { it.isFiringNow }.minWithOrNull(soonest)?.let { return State(it, Phase.RUNNING, others) }
        jobs.filter { it.nextFireAt != null }.minWithOrNull(soonest)?.let { return State(it, Phase.COUNTDOWN, others) }
        val waiting = jobs.minWith(compareBy({ it.title }, { it.id }))
        return State(waiting, Phase.WAITING, others)
    }

    fun countdown(remainingMs: Long): String? {
        val s = ((remainingMs.coerceAtLeast(0) + 999) / 1000)
        if (s >= 3600) return null
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    data class Coarse(val days: Long, val hours: Long, val minutes: Long)

    fun coarse(remainingMs: Long): Coarse {
        val s = (remainingMs.coerceAtLeast(0) + 999) / 1000
        if (s < 86_400) {
            val mins = (s + 59) / 60
            return Coarse(0, mins / 60, mins % 60)
        }
        return Coarse(s / 86_400, (s % 86_400) / 3600, 0)
    }

    fun progress(job: PillJob, now: Long): Float {
        val end = job.nextFireAt ?: return 0f
        val total = end - job.windowStart
        if (total <= 0) return 1f
        return ((now - job.windowStart).toFloat() / total).coerceIn(0f, 1f)
    }

    fun runNumber(job: PillJob, running: Boolean): Int? {
        if (!job.repeats) return null
        val n = if (running) maxOf(1, job.runsDone) else job.runsDone + 1
        return job.runTotal?.let { minOf(n, it) } ?: n
    }

    fun runCounter(job: PillJob, running: Boolean): String? {
        val n = runNumber(job, running) ?: return null
        return job.runTotal?.let { "$n/$it" } ?: "#$n"
    }

    fun widthTemplate(clock: String): String {
        val out = StringBuilder()
        var run = 0
        fun flush() {
            if (run > 0) repeat(maxOf(2, run)) { out.append('0') }
            run = 0
        }
        for (ch in clock) {
            if (ch in '0'..'9') run++ else { flush(); out.append(ch) }
        }
        flush()
        return out.toString()
    }

    fun isDismissed(live: Set<String>, hidden: Set<String>): Boolean =
        live.isNotEmpty() && hidden.containsAll(live)


    fun jobs(
        sessionId: String,
        tasks: List<ScheduledTask>,
        runningTaskIds: Set<String>,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
    ): List<PillJob> = tasks.mapNotNull { t ->
        val firing = t.id in runningTaskIds
        val held = ScheduledSessionHold.heldSession(t, now, upstreamUnfinished) == sessionId
        val runningHere = firing && ScheduledSessionHold.runHold(t) == sessionId
        if (!held && !runningHere) return@mapNotNull null
        pillJob(t, now, firing)
    }

    fun pillJob(t: ScheduledTask, now: Long, firing: Boolean): PillJob {
        val next = t.nextTriggerMs(now)
        val interval = t.triggerKind == ScheduledTriggerKind.INTERVAL
        val repeats = interval || (t.isCalendar && t.repeatMode != ScheduledRepeatMode.ONCE)
        val windowStart = when {
            interval && next != null && t.intervalSec != null -> next - t.intervalSec * 1000
            t.usesAnchor -> t.anchorMs ?: t.createdAt
            else -> t.lastFiredAt ?: t.createdAt
        }
        val runsDone = if (interval) t.triggeredCount ?: 0 else t.firesSoFar + if (firing) 1 else 0
        return PillJob(
            id = t.id,
            title = t.label.ifBlank { t.prompt.take(40) },
            nextFireAt = next,
            windowStart = windowStart,
            remaining = t.remainingFires,
            isFiringNow = firing,
            runsDone = runsDone,
            runTotal = if (interval) t.maxFires else null,
            repeats = repeats,
        )
    }
}
