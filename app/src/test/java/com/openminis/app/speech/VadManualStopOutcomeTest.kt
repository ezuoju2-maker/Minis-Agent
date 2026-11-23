package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class VadManualStopOutcomeTest {

    private val floor = 0.3f

    @Test
    fun `held speech is transcribed`() {
        assertEquals(
            VadManualStopOutcome.TRANSCRIBE,
            VadManualStopOutcome.decide(hasSession = true, heldSeconds = 1.8f, floorSeconds = floor),
        )
    }

    @Test
    fun `a sub-floor take reports no match rather than going silent`() {
        assertEquals(
            VadManualStopOutcome.NO_MATCH,
            VadManualStopOutcome.decide(hasSession = true, heldSeconds = 0.1f, floorSeconds = floor),
        )
    }

    @Test
    fun `nothing captured still reports no match, never nothing`() {
        assertEquals(
            VadManualStopOutcome.NO_MATCH,
            VadManualStopOutcome.decide(hasSession = true, heldSeconds = 0f, floorSeconds = floor),
        )
    }

    @Test
    fun `exactly at the floor counts as too short`() {
        assertEquals(
            VadManualStopOutcome.NO_MATCH,
            VadManualStopOutcome.decide(hasSession = true, heldSeconds = floor, floorSeconds = floor),
        )
    }

    @Test
    fun `no session is a no-op — the silence path already settled it`() {
        assertEquals(
            VadManualStopOutcome.NOTHING_TO_DO,
            VadManualStopOutcome.decide(hasSession = false, heldSeconds = 5f, floorSeconds = floor),
        )
    }

    @Test
    fun `every outcome with a session ends in a terminal callback`() {
        for (held in listOf(0f, 0.05f, floor, 0.5f, 3f, 120f)) {
            val outcome = VadManualStopOutcome.decide(true, held, floor)
            assertEquals(
                "held=$held must not fall through silently",
                true,
                outcome == VadManualStopOutcome.TRANSCRIBE || outcome == VadManualStopOutcome.NO_MATCH,
            )
        }
    }
}
