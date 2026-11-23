package com.openminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorativeAnimationTest {

    private val ms = 1_000_000L

    @Test
    fun `phase walks 0 to 1 over one period and wraps`() {
        assertEquals(0f, decorativePhase(0L, 1000), 1e-6f)
        assertEquals(0.25f, decorativePhase(250 * ms, 1000), 1e-6f)
        assertEquals(0.5f, decorativePhase(500 * ms, 1000), 1e-6f)
        assertEquals(0f, decorativePhase(1000 * ms, 1000), 1e-6f)
        assertEquals(0.1f, decorativePhase(1100 * ms, 1000), 1e-6f)
    }

    @Test
    fun `offset staggers the phase and stays in range for negative shifts`() {
        assertEquals(0f, decorativePhase(120 * ms, 700, 120), 1e-6f)
        val p = decorativePhase(0L, 700, 120)
        assertTrue("$p", p >= 0f && p < 1f)
        assertEquals((700f - 120f) / 700f, p, 1e-5f)
    }

    @Test
    fun `phase never reaches 1 and is safe on a non-positive period`() {
        val p = decorativePhase(999_999_999L, 1000)
        assertTrue("$p", p < 1f)
        assertEquals(0f, decorativePhase(12345L, 0), 0f)
        assertEquals(0f, decorativePhase(12345L, -5), 0f)
    }

    @Test
    fun `ping pong is the Reverse repeat shape`() {
        assertEquals(0f, decorativePingPong(0f), 1e-6f)
        assertEquals(0.5f, decorativePingPong(0.25f), 1e-6f)
        assertEquals(1f, decorativePingPong(0.5f), 1e-6f)
        assertEquals(0.5f, decorativePingPong(0.75f), 1e-6f)
        assertEquals(0.5f, decorativePingPong(1.25f), 1e-6f)
    }

    @Test
    fun `clock interval is a throttle below the panel rate`() {
        assertTrue(DecorativeClock.INTERVAL_NS >= 30_000_000L)
        assertTrue(DecorativeClock.INTERVAL_NS <= 40_000_000L)
    }
}
