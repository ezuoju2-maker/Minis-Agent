package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPulseGateTest {

    @Test
    fun `a fresh session that never fell back does not pulse`() {
        assertFalse(shouldPulse(current = 0, baseline = 0))
    }

    @Test
    fun `a fallback while the screen is open pulses`() {
        assertTrue(shouldPulse(current = 1, baseline = 0))
    }

    @Test
    fun `re-entering a session that fell back earlier does NOT pulse`() {
        assertFalse(shouldPulse(current = 3, baseline = 3))
    }

    @Test
    fun `a further fallback after re-entry still pulses`() {
        assertTrue(shouldPulse(current = 4, baseline = 3))
    }

    @Test
    fun `several fallbacks in one visit each clear the gate`() {
        assertTrue(shouldPulse(current = 5, baseline = 3))
        assertTrue(shouldPulse(current = 6, baseline = 3))
    }

    @Test
    fun `a counter that somehow moves backwards does not pulse`() {
        assertFalse(shouldPulse(current = 1, baseline = 3))
    }
}
