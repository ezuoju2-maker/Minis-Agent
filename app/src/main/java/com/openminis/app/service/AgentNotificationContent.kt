package com.openminis.app.service

import androidx.annotation.DrawableRes
import com.openminis.app.R
import com.openminis.app.ui.chat.friendlyToolTitleFor
import com.openminis.app.ui.chat.toolIconResFor

internal enum class AgentPhase { COMPLETED, TOOL, THINKING, GENERATING, IDLE, WAITING, ASKING }

internal fun resolveAgentPhase(
    isCompleted: Boolean,
    toolName: String?,
    isThinking: Boolean,
    hasActiveSessions: Boolean,
    isScheduledWaiting: Boolean = false,
    isAwaitingAnswer: Boolean = false,
): AgentPhase = when {
    isAwaitingAnswer -> AgentPhase.ASKING
    isScheduledWaiting && !hasActiveSessions -> AgentPhase.WAITING
    isCompleted -> AgentPhase.COMPLETED
    toolName != null -> AgentPhase.TOOL
    !hasActiveSessions -> AgentPhase.IDLE
    isThinking -> AgentPhase.THINKING
    else -> AgentPhase.GENERATING
}

@DrawableRes
internal fun notificationSmallIconFor(phase: AgentPhase, toolName: String?, waitHasClock: Boolean = true): Int = when (phase) {
    AgentPhase.WAITING -> if (waitHasClock) R.drawable.ic_notification_scheduled else R.drawable.ic_notification_hourglass
    AgentPhase.COMPLETED -> R.drawable.ic_notification_completed
    AgentPhase.TOOL -> toolIconResFor(toolName)
    AgentPhase.THINKING -> R.drawable.ic_tool_psychology
    AgentPhase.ASKING -> R.drawable.ic_notification_question
    AgentPhase.GENERATING, AgentPhase.IDLE -> R.drawable.ic_launcher_monochrome
}

internal fun toolRowTitle(toolName: String, toolTitle: String?): String =
    toolTitle?.takeIf { it.isNotBlank() } ?: friendlyToolTitleFor(toolName)

internal fun humanizeToolStatus(status: String, toolName: String?): String {
    if (toolName == null) return status
    if (status != "Running: $toolName") return status
    return friendlyToolTitleFor(toolName)
}

internal fun chipTimerText(elapsedMs: Long): String {
    val total = (elapsedMs / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

internal fun waitStatusText(nextRunLabel: String, runCounter: String?): String =
    runCounter?.let { "$nextRunLabel · $it" } ?: nextRunLabel

internal data class NotificationClock(
    val showWhen: Boolean,
    val whenMs: Long,
    val usesChronometer: Boolean,
    val countDown: Boolean,
)

internal fun notificationClockFor(
    phase: AgentPhase,
    runStartWallMs: Long,
    finishedWallMs: Long,
    nextRunWallMs: Long?,
    nowWallMs: Long,
    presenceOnly: Boolean = false,
    askExpiresWallMs: Long? = null,
): NotificationClock = when {
    presenceOnly ->
        NotificationClock(showWhen = false, whenMs = nowWallMs, usesChronometer = false, countDown = false)
    phase == AgentPhase.ASKING -> clockForPhase(AgentPhase.WAITING, runStartWallMs, finishedWallMs, askExpiresWallMs, nowWallMs)
    else -> clockForPhase(phase, runStartWallMs, finishedWallMs, nextRunWallMs, nowWallMs)
}

internal data class AskingState(val sessionTitle: String?, val expiresAtMs: Long?)

internal fun askingStateFor(asks: Collection<com.openminis.app.agent.userask.PendingAsk>): AskingState? =
    asks.filter { it.state == com.openminis.app.agent.userask.UserAskState.PENDING }
        .minWithOrNull(compareBy(nullsLast()) { it.expiresAtMs })
        ?.let { AskingState(it.sessionTitle?.takeIf { t -> t.isNotBlank() }, it.expiresAtMs) }

internal fun askChipText(remainingMs: Long?): String = when {
    remainingMs == null -> "?"
    remainingMs >= 3_600_000L -> waitChipText(remainingMs) ?: "?"
    else -> chipTimerText(remainingMs.coerceAtLeast(0))
}

internal fun isPresenceOnly(phase: AgentPhase, hasScheduledHold: Boolean): Boolean =
    phase == AgentPhase.IDLE && !hasScheduledHold

private fun clockForPhase(
    phase: AgentPhase,
    runStartWallMs: Long,
    finishedWallMs: Long,
    nextRunWallMs: Long?,
    nowWallMs: Long,
): NotificationClock = when (phase) {
    AgentPhase.WAITING ->
        if (nextRunWallMs != null && nextRunWallMs > nowWallMs) {
            NotificationClock(showWhen = true, whenMs = nextRunWallMs, usesChronometer = true, countDown = true)
        } else {
            NotificationClock(showWhen = false, whenMs = nowWallMs, usesChronometer = false, countDown = false)
        }
    AgentPhase.COMPLETED ->
        NotificationClock(showWhen = true, whenMs = finishedWallMs, usesChronometer = false, countDown = false)
    else -> NotificationClock(showWhen = true, whenMs = runStartWallMs, usesChronometer = true, countDown = false)
}

internal fun waitChipText(remainingMs: Long?): String? {
    if (remainingMs == null || remainingMs <= 0) return null
    val minutes = (remainingMs + 59_999) / 60_000
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h${minutes % 60}m"
        else -> "${minutes / (24 * 60)}d${(minutes % (24 * 60)) / 60}h"
    }
}

internal fun waitRefreshDelayMs(remainingMs: Long?, promotedChip: Boolean): Long? {
    if (remainingMs == null || remainingMs <= 0) return null
    if (!promotedChip) return remainingMs + WAIT_REFRESH_MARGIN_MS
    val toBoundary = remainingMs % 60_000
    return (if (toBoundary == 0L) 60_000 else toBoundary) + WAIT_REFRESH_MARGIN_MS
}

private const val WAIT_REFRESH_MARGIN_MS = 250L

internal fun withRunCounter(status: String, runCounter: String?): String =
    runCounter?.let { "$status · $it" } ?: status

internal fun overlayWaitFor(
    wait: com.openminis.app.scheduled.ScheduledWait?,
    capsuleSessionId: String?,
): com.openminis.app.scheduled.ScheduledWait? =
    wait?.takeIf { capsuleSessionId == null || it.sessionId == capsuleSessionId }

internal fun wallClockToElapsedRealtime(wallMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long =
    nowElapsedMs + (wallMs - nowWallMs)

@androidx.annotation.StringRes
internal fun completedTextResFor(sessionCount: Int): Int =
    if (sessionCount > 0) R.string.bg_service_notification_text_completed
    else R.string.bg_service_notification_text_completed_no_count
