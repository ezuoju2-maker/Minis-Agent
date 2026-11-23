package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFollowDeadbandTest {

    private val D = StreamFollowDeadband.SHRINK_DEADBAND_PX
    private val T = StreamFollowDeadband.SHRINK_SETTLE_MS


    @Test
    fun `small reversal during active growth is suppressed`() {
        assertTrue(
            "an 8px reversal mid-stream is a text reflow, not the user's content moving",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = 40L),
        )
    }

    @Test
    fun `growth is always followed`() {
        assertFalse(
            "the common path: content grew, the viewport must catch up",
            StreamFollowDeadband.shouldSuppress(current = 140f, previous = 100f, msSinceGrowth = 0L),
        )
    }

    @Test
    fun `no change is not suppressed`() {
        assertFalse(
            StreamFollowDeadband.shouldSuppress(current = 100f, previous = 100f, msSinceGrowth = 0L),
        )
    }


    @Test
    fun `large reversal is followed even during active growth`() {
        assertFalse(
            "a large reversal is a real layout change and must not be swallowed",
            StreamFollowDeadband.shouldSuppress(current = 100f, previous = 400f, msSinceGrowth = 0L),
        )
    }

    @Test
    fun `reversal exactly at the threshold is followed`() {
        assertFalse(
            "the deadband is exclusive — at the threshold we defer to real layout",
            StreamFollowDeadband.shouldSuppress(
                current = 100f, previous = 100f + D, msSinceGrowth = 0L,
            ),
        )
    }

    @Test
    fun `just inside the threshold is suppressed`() {
        assertTrue(
            StreamFollowDeadband.shouldSuppress(
                current = 100f, previous = 100f + D - 1f, msSinceGrowth = 0L,
            ),
        )
    }


    @Test
    fun `small reversal is accepted once the stream goes quiet`() {
        assertFalse(
            "after the settle window a small reversal is the new resting position; " +
                "suppressing forever would strand the viewport off-bottom",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = T),
        )
    }

    @Test
    fun `settle boundary is inclusive`() {
        assertTrue(
            "one ms before the window closes, still suppressed",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = T - 1),
        )
    }


    private fun replay(series: List<Pair<Float, Long>>): List<Boolean> {
        var lastDist = series.first().first
        var lastGrowMs = 0L
        val acted = mutableListOf<Boolean>()
        for ((dist, t) in series) {
            val suppressed = StreamFollowDeadband.shouldSuppress(dist, lastDist, t - lastGrowMs)
            acted += !suppressed
            if (!suppressed) {
                if (dist > lastDist) lastGrowMs = t
                lastDist = dist
            }
        }
        return acted
    }

    @Test
    fun `grow then reflow then grow suppresses only the reflow`() {
        val acted = replay(
            listOf(0f to 0L, 90f to 120L, 82f to 240L, 150f to 360L, 240f to 480L),
        )
        assertTrue("initial growth followed", acted[1])
        assertFalse("the 8px reflow dip is skipped", acted[2])
        assertTrue("growth after the dip is followed again", acted[3])
        assertTrue(acted[4])
    }

    @Test
    fun `grow then large shrink is followed`() {
        val acted = replay(listOf(0f to 0L, 400f to 120L, 100f to 240L))
        assertTrue(acted[1])
        assertTrue("a 300px collapse must be followed, not swallowed", acted[2])
    }
}
