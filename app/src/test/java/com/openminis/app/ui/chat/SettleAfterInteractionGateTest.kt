package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettleAfterInteractionGateTest {

    private val W = SettleAfterInteractionGate.RECENT_DRAG_MS

    private fun gate(
        scrollInProgress: Boolean = false,
        userScrolledAway: Boolean = false,
        nearBottom: Boolean = true,
        streaming: Boolean = true,
        msSinceDrag: Long = 200L,
    ) = SettleAfterInteractionGate.shouldSettle(
        scrollInProgress, userScrolledAway, nearBottom, streaming, msSinceDrag,
    )


    @Test
    fun `a follow glide ending with no recent drag does not settle`() {
        assertFalse(gate(msSinceDrag = Long.MAX_VALUE / 2))
        assertFalse(gate(msSinceDrag = W + 1))
    }


    @Test
    fun `a drag that just ended near the bottom while streaming settles`() {
        assertTrue(gate(msSinceDrag = 0))
        assertTrue(gate(msSinceDrag = 400))
        assertTrue("window edge is inclusive", gate(msSinceDrag = W))
    }

    @Test
    fun `a fling that outlives the finger still counts as that drag`() {
        assertTrue(gate(msSinceDrag = W - 1))
    }


    @Test
    fun `never settles while a scroll is still in progress`() {
        assertFalse(gate(scrollInProgress = true))
    }

    @Test
    fun `never settles when the user has scrolled away`() {
        assertFalse(gate(userScrolledAway = true))
    }

    @Test
    fun `never settles when not near the bottom`() {
        assertFalse(gate(nearBottom = false))
    }

    @Test
    fun `never settles when nothing is streaming`() {
        assertFalse(gate(streaming = false))
    }

    @Test
    fun `a negative clock delta is treated as no drag`() {
        assertFalse(gate(msSinceDrag = -1))
    }
}
