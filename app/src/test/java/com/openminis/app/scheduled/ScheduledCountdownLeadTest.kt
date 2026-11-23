package com.openminis.app.scheduled

import com.openminis.app.data.repository.BackgroundSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledCountdownLeadTest {

    private val now = 1_800_000_000_000L
    private val none: (String) -> Boolean = { false }
    private val minute = 60_000L

    private fun after(delaySec: Long, id: String = "a", target: String = "S") = ScheduledTask(
        id = id, label = "later", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE, customDays = emptySet(), prompt = "p",
        targetMode = ScheduledTargetMode.AppendToSession(target), enabled = true,
        triggerKind = ScheduledTriggerKind.AFTER, delaySec = delaySec, anchorMs = now,
        creatorSessionId = "S",
    )

    private fun onCompletion() = ScheduledTask(
        id = "c", label = "then", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE, customDays = emptySet(), prompt = "p",
        targetMode = ScheduledTargetMode.AppendToSession("S"), enabled = true,
        triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = "up",
        creatorSessionId = "S",
    )

    @Test fun `the default lead is one minute and the cases map to their spans`() {
        assertEquals(1, BackgroundSettingsRepository.DEFAULT_SCHEDULED_COUNTDOWN_LEAD)
        assertEquals(listOf("off", "1m", "5m", "15m", "always"), BackgroundSettingsRepository.SCHEDULED_COUNTDOWN_CASES)
        assertEquals(
            listOf(0L, minute, 5 * minute, 15 * minute, Long.MAX_VALUE),
            (0..4).map(BackgroundSettingsRepository::scheduledCountdownLeadMs),
        )
    }

    @Test fun `a run further away than the lead holds nothing, inside it holds the chat`() {
        val t = after(delaySec = 600)
        assertNull(ScheduledSessionHold.heldSession(t, now, none, minute))
        assertNull(ScheduledSessionHold.heldSession(t, now, none, 5 * minute))
        assertEquals("S", ScheduledSessionHold.heldSession(t, now, none, 15 * minute))
        assertEquals("S", ScheduledSessionHold.heldSession(t, now + 570_000, none, minute))
    }

    @Test fun `whole wait keeps the old behavior and off never holds`() {
        val t = after(delaySec = 86_400)
        assertEquals("S", ScheduledSessionHold.heldSession(t, now, none))
        assertEquals("S", ScheduledSessionHold.heldSession(t, now, none, Long.MAX_VALUE))
        assertNull(ScheduledSessionHold.heldSession(after(delaySec = 10), now, none, 0L))
    }

    @Test fun `an on-completion task has no clock - it holds unless the countdown is off`() {
        val up: (String) -> Boolean = { it == "up" }
        assertEquals("S", ScheduledSessionHold.heldSession(onCompletion(), now, up, minute))
        assertNull(ScheduledSessionHold.heldSession(onCompletion(), now, up, 0L))
    }

    @Test fun `the waiting card follows the same window`() {
        val t = after(delaySec = 600)
        assertNull(ScheduledWaitModel.pick(listOf(t), now, minute, none))
        val w = ScheduledWaitModel.pick(listOf(t), now + 570_000, minute, none)!!
        assertEquals("S", w.sessionId)
        assertEquals(now + 600_000, w.nextRunMs)
    }

    @Test fun `the next re-derivation is when the soonest outside task enters its window`() {
        val soon = after(delaySec = 600, id = "x")
        val later = after(delaySec = 3_600, id = "y")
        assertEquals(now + 600_000 - minute, ScheduledSessionHold.nextWindowStart(listOf(later, soon), now, minute))
        assertNull(ScheduledSessionHold.nextWindowStart(listOf(soon), now + 590_000, minute))
        assertNull(ScheduledSessionHold.nextWindowStart(listOf(soon), now, 0L))
        assertNull(ScheduledSessionHold.nextWindowStart(listOf(soon), now, Long.MAX_VALUE))
        assertNull(ScheduledSessionHold.nextWindowStart(listOf(after(600, target = "Other")), now, minute))
    }
}
