package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineBudgetTest {

    @Test
    fun `remaining decreases and clamps at zero`() {
        var now = 0L
        val b = DeadlineBudget(totalMs = 10_000, clock = { now }, startAt = 0L)
        assertEquals(10_000L, b.remaining())
        now = 4_000L
        assertEquals(6_000L, b.remaining())
        now = 99_999L
        assertEquals(0L, b.remaining())
        assertTrue(b.expired())
    }

    @Test
    fun `slice never exceeds remaining or desired`() {
        var now = 0L
        val b = DeadlineBudget(totalMs = 10_000, clock = { now }, startAt = 0L)
        assertEquals(5_000L, b.sliceFor(desiredMs = 60_000)) // fair share, not desired
        assertEquals(2_000L, b.sliceFor(desiredMs = 2_000))  // desired smaller
        now = 8_000L
        // 2000 left: fair share gives 1000 now, reserving 1000 for a later step.
        assertEquals(1_000L, b.sliceFor(desiredMs = 9_000))
    }

    @Test
    fun `slice returns zero when budget nearly gone`() {
        var now = 9_500L
        val b = DeadlineBudget(totalMs = 10_000, clock = { now }, startAt = 0L, )
        assertEquals(0L, b.sliceFor(desiredMs = 5_000, minMs = 1_000L))
    }

    @Test
    fun `urgency escalates as time runs out`() {
        var now = 0L
        val b = DeadlineBudget(totalMs = 10_000, clock = { now }, startAt = 0L)
        assertEquals(DeadlineBudget.Urgency.NORMAL, b.urgency())
        assertNull(b.hint())
        now = 7_500L
        assertEquals(DeadlineBudget.Urgency.WRAP_UP, b.urgency())
        assertTrue(b.hint()!!.contains("wrap up"))
        now = 9_500L
        assertEquals(DeadlineBudget.Urgency.CRITICAL, b.urgency())
        assertTrue(b.hint()!!.contains("final summary"))
    }

    @Test
    fun `consumed fraction bounded`() {
        var now = -5_000L
        val b = DeadlineBudget(totalMs = 10_000, clock = { now }, startAt = 0L)
        assertEquals(0.0, b.consumedFraction(), 0.0001)
        now = 5_000L
        assertEquals(0.5, b.consumedFraction(), 0.0001)
        now = 50_000L
        assertEquals(1.0, b.consumedFraction(), 0.0001)
    }
}
