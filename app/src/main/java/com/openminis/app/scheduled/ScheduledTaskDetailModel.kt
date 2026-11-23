package com.openminis.app.scheduled

import java.util.Calendar

object ScheduledTaskDetailModel {

    enum class Status { RUNNING, WAITING, COMPLETED, FAILED, DISABLED, DELETED }

    enum class FireState { OK, FAILED, RUNNING }

    data class FireRow(
        val index: Int,
        val firedAt: Long,
        val sessionId: String?,
        val state: FireState,
        val preview: String?,
        val isCurrent: Boolean,
        val inOtherSession: Boolean,
    )

    data class Progress(
        val currentIndex: Int?,
        val total: Int?,
        val remaining: Int?,
        val nextFireAt: Long?,
        val firesSoFar: Int,
    ) {
        val visible: Boolean get() = currentIndex != null || total != null
    }

    private const val REMAINING_WALK_CAP = 1000

    fun isFireRunning(task: ScheduledTask?, marker: ScheduledTaskMarker, hostStreaming: Boolean): Boolean {
        val firedAt = marker.firedAtMs ?: return false
        val recorded = task?.runHistory?.any { it.firedAt == firedAt } == true
        return !recorded && hostStreaming
    }

    fun status(task: ScheduledTask?, fireRunning: Boolean, now: Long): Status {
        if (task == null) return Status.DELETED
        if (fireRunning) return Status.RUNNING
        val next = task.nextTriggerMs(now)
        if (task.isOneShot) {
            val waiting = next != null || task.triggerKind == ScheduledTriggerKind.ON_COMPLETION
            if (task.enabled && waiting) return Status.WAITING
            val last = task.runHistory.firstOrNull() ?: return Status.DISABLED
            return if (last.ok) Status.COMPLETED else Status.FAILED
        }
        if (task.enabled) return if (next != null) Status.WAITING else Status.COMPLETED
        return if (windowEnded(task, now)) Status.COMPLETED else Status.DISABLED
    }

    private fun windowEnded(task: ScheduledTask, now: Long): Boolean =
        task.remainingFires == 0 ||
            (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)

    fun progress(task: ScheduledTask?, marker: ScheduledTaskMarker, fireRunning: Boolean, now: Long): Progress {
        if (task == null) return Progress(null, null, null, null, 0)
        val fired = task.firesSoFar
        val next = if (task.enabled) task.nextTriggerMs(now) else null
        val remaining: Int? = when {
            task.triggerKind == ScheduledTriggerKind.ON_COMPLETION -> if (task.enabled) 1 else 0
            next == null -> 0
            task.isOneShot -> 1
            task.triggerKind == ScheduledTriggerKind.INTERVAL -> task.remainingFires
            task.endDateMs == null -> null
            else -> countFires(task, now)
        }
        val inFlight = if (fireRunning) 1 else 0
        val total = remaining?.let { fired + inFlight + it }
            ?.takeIf {
                task.isOneShot || task.endDateMs != null ||
                    (task.triggerKind == ScheduledTriggerKind.INTERVAL && task.maxFires != null)
            }
        return Progress(
            currentIndex = currentIndex(task, marker, fireRunning),
            total = total,
            remaining = remaining,
            nextFireAt = next,
            firesSoFar = fired,
        )
    }

    private fun countFires(task: ScheduledTask, now: Long): Int {
        var n = 0
        var t = task.nextTriggerMs(now) ?: return 0
        while (n < REMAINING_WALK_CAP) {
            n++
            t = task.nextTriggerMs(t + 60_000L) ?: break
        }
        return n
    }

    private fun currentIndex(task: ScheduledTask, marker: ScheduledTaskMarker, fireRunning: Boolean): Int? {
        val firedAt = marker.firedAtMs ?: return null
        val pos = task.runHistory.indexOfFirst { it.firedAt == firedAt }
        if (pos >= 0) return task.firesSoFar - pos
        return if (fireRunning) task.firesSoFar + 1 else null
    }

    fun history(
        task: ScheduledTask?,
        marker: ScheduledTaskMarker,
        hostSessionId: String?,
        fireRunning: Boolean,
    ): List<FireRow> {
        val runs = task?.runHistory ?: emptyList()
        val fired = task?.firesSoFar ?: runs.size
        val rows = runs.mapIndexed { pos, run ->
            FireRow(
                index = fired - pos,
                firedAt = run.firedAt,
                sessionId = run.sessionId,
                state = if (run.ok) FireState.OK else FireState.FAILED,
                preview = run.preview,
                isCurrent = marker.firedAtMs != null && run.firedAt == marker.firedAtMs,
                inOtherSession = run.sessionId != null && hostSessionId != null && run.sessionId != hostSessionId,
            )
        }
        val runningAt = marker.firedAtMs
        if (!fireRunning || runningAt == null || rows.any { it.firedAt == runningAt }) return rows
        val live = FireRow(
            index = fired + 1,
            firedAt = runningAt,
            sessionId = hostSessionId,
            state = FireState.RUNNING,
            preview = null,
            isCurrent = true,
            inOtherSession = false,
        )
        return listOf(live) + rows
    }


    data class TriggerPhrases(
        val once: String,
        val daily: String,
        val weekdays: String,
        val weekly: String,
        val daySeparator: String,
        val dayNames: List<String>,
        val from: String,
        val until: String,
        val window: String,
        val interval: String = "Every %1${'$'}s",
        val intervalCount: String = "Every %1${'$'}s · %2${'$'}d times",
        val onCompletion: String = "When \u201c%1${'$'}s\u201d finishes",
    )

    private val WEEK_ORDER = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
    )

    fun triggerText(
        task: ScheduledTask,
        p: TriggerPhrases,
        formatTime: (hour: Int, minute: Int) -> String,
        upstreamName: (String) -> String? = { null },
        formatDate: (Long) -> String,
    ): String {
        when (task.triggerKind) {
            ScheduledTriggerKind.AFTER -> {
                val at = (task.anchorMs ?: task.createdAt) + (task.delaySec ?: 0L) * 1000
                val cal = Calendar.getInstance().apply { timeInMillis = at }
                return p.once.format(
                    "${formatDate(at)} ${formatTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}",
                )
            }
            ScheduledTriggerKind.INTERVAL -> {
                val every = formatDuration(task.intervalSec ?: 0L)
                return task.maxFires?.let { p.intervalCount.format(every, it) } ?: p.interval.format(every)
            }
            ScheduledTriggerKind.ON_COMPLETION -> {
                val of = task.onCompletionOf.orEmpty()
                return p.onCompletion.format(upstreamName(of) ?: of.take(8))
            }
            ScheduledTriggerKind.CALENDAR -> Unit
        }
        val time = formatTime(task.timeOfDayHour, task.timeOfDayMinute)
        val base = when (task.repeatMode) {
            ScheduledRepeatMode.ONCE -> {
                val at = task.startDateMs?.let { "${formatDate(it)} $time" } ?: time
                p.once.format(at)
            }
            ScheduledRepeatMode.DAILY -> p.daily.format(time)
            ScheduledRepeatMode.WEEKDAYS -> p.weekdays.format(time)
            ScheduledRepeatMode.CUSTOM -> {
                val days = WEEK_ORDER.filter { it in task.customDays }
                    .joinToString(p.daySeparator) { p.dayNames[it - 1] }
                p.weekly.format(days, time)
            }
        }
        if (task.repeatMode == ScheduledRepeatMode.ONCE) return base
        val start = task.startDateMs
        val end = task.endDateMs
        val window = when {
            start != null && end != null -> p.window.format(formatDate(start), formatDate(end))
            end != null -> p.until.format(formatDate(end))
            start != null -> p.from.format(formatDate(start))
            else -> null
        }
        return if (window == null) base else "$base · $window"
    }

    fun formatDuration(sec: Long): String {
        if (sec <= 0) return "0s"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return buildString {
            if (h > 0) append("${h}h")
            if (m > 0) append("${m}m")
            if (s > 0 || isEmpty()) append("${s}s")
        }
    }


    data class CardPhrases(
        val run: String,
        val left: String,
        val next: String,
        val complete: String,
        val cancelled: String,
    )

    const val CARD_SEPARATOR = " · "

    fun cardSubline(
        marker: ScheduledTaskMarker,
        cancelled: Boolean,
        now: Long,
        p: CardPhrases,
        live: Boolean = true,
        relative: (atMs: Long) -> String,
    ): String {
        val parts = mutableListOf<String>()
        marker.fireIndex?.takeIf { it > 0 }?.let { parts += p.run.format(it) }
        marker.remaining?.let { parts += p.left.format(it) }
        val next = marker.nextFireAtMs?.takeIf { it > now }
        when {
            cancelled -> parts += p.cancelled
            next != null -> parts += if (live) p.next.format(relative(next)) else p.cancelled
            isLastFire(marker) -> parts += p.complete
        }
        marker.triggerLabel?.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (parts.isEmpty()) {
            marker.prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { parts += it }
        }
        return parts.joinToString(CARD_SEPARATOR)
    }

    enum class RelativeUnit { SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

    fun relativeUnit(seconds: Long): Pair<Long, RelativeUnit> {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> s to RelativeUnit.SECOND
            s < 3_600 -> s / 60 to RelativeUnit.MINUTE
            s < 86_400 -> s / 3_600 to RelativeUnit.HOUR
            s < 7 * 86_400 -> s / 86_400 to RelativeUnit.DAY
            s < 30 * 86_400 -> s / (7 * 86_400) to RelativeUnit.WEEK
            s < 365 * 86_400 -> s / (30 * 86_400) to RelativeUnit.MONTH
            else -> s / (365 * 86_400) to RelativeUnit.YEAR
        }
    }

    fun nextTickMs(waitMs: Long): Long =
        if (waitMs <= 60_000L) waitMs + 1 else (waitMs % 60_000L).let { if (it == 0L) 60_000L else it } + 1

    fun isLastFire(marker: ScheduledTaskMarker): Boolean =
        marker.nextFireAtMs == null || marker.remaining == 0


    enum class DurationUnit { DAY, HOUR, MINUTE, SECOND }

    fun durationParts(totalSeconds: Long): List<Pair<DurationUnit, Int>> {
        val s = totalSeconds.coerceAtLeast(0)
        val units = listOf(
            DurationUnit.DAY to s / 86_400,
            DurationUnit.HOUR to (s % 86_400) / 3_600,
            DurationUnit.MINUTE to (s % 3_600) / 60,
            DurationUnit.SECOND to s % 60,
        )
        val first = units.indexOfFirst { it.second > 0 }
        if (first < 0) return listOf(DurationUnit.SECOND to 0)
        return units.subList(first, minOf(first + 2, units.size))
            .filterIndexed { i, (_, n) -> i == 0 || n > 0 }
            .map { (u, n) -> u to n.toInt() }
    }


    sealed class Delivery {
        object NewSession : Delivery()
        object ThisChat : Delivery()
        data class OtherChat(val sessionId: String) : Delivery()
        data class Rerun(val sessionId: String, val inThisChat: Boolean) : Delivery()
        object ChildOfThisChat : Delivery()
        data class ChildOfOtherChat(val sessionId: String) : Delivery()
    }

    fun delivery(mode: ScheduledTargetMode, hostSessionId: String?): Delivery = when (mode) {
        ScheduledTargetMode.NewSession -> Delivery.NewSession
        is ScheduledTargetMode.AppendToSession ->
            if (mode.sessionId == hostSessionId) Delivery.ThisChat else Delivery.OtherChat(mode.sessionId)
        is ScheduledTargetMode.RerunMessage -> Delivery.Rerun(mode.sessionId, mode.sessionId == hostSessionId)
        is ScheduledTargetMode.ChildOfCurrent ->
            if (mode.sessionId == hostSessionId) Delivery.ChildOfThisChat else Delivery.ChildOfOtherChat(mode.sessionId)
    }


    fun cleanPrompt(task: ScheduledTask?, marker: ScheduledTaskMarker): String {
        val raw = task?.prompt?.takeIf { it.isNotBlank() } ?: marker.prompt
        var s = raw.trimStart()
        while (true) {
            s = when {
                s.startsWith("<system-reminder>") && s.contains("</system-reminder>") ->
                    s.substringAfter("</system-reminder>").trimStart()
                LEADING_NOTE.containsMatchIn(s) -> s.replaceFirst(LEADING_NOTE, "").trimStart()
                else -> return s.trimEnd()
            }
        }
    }

    private val LEADING_NOTE = Regex("""^\[(?:Scheduled task\b|This task's first step already ran\b)[^\]]*]""")
}
