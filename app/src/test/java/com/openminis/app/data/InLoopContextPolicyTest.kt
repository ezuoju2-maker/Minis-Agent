package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InLoopContextPolicyTest {

    @Test
    fun `large windows can auto-compact`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(p.compactThreshold, window),
        )
    }

    @Test
    fun `mid windows can auto-compact`() {
        val window = 100_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(p.compactThreshold + 1, window),
        )
    }

    @Test
    fun `small windows never ask for compaction - only exhaustion`() {
        val window = 40_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(0, p.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            p.check(window - 1_000, window),
        )
    }

    @Test
    fun `tiny windows never ask for compaction - only exhaustion`() {
        val window = 16_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(0, p.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            p.check(window - 100, window),
        )
    }

    @Test
    fun `an idle context proceeds untouched`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(ContextPolicy.CheckResult.OK, p.check(1_000, window))
    }

    @Test
    fun `compaction is only requested at or above the threshold`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(
            ContextPolicy.CheckResult.OK,
            p.check(p.compactThreshold - 1, window),
        )
    }
}
