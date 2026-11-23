package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotDisclaimerGateTest {

    private class GateHarness(private val isCopilot: Boolean) {
        var showDisclaimer = false
            private set
        var deviceFlowStarts = 0
            private set

        fun tapSignIn() {
            if (isCopilot) showDisclaimer = true else beginSignIn()
        }

        fun tapAccept() {
            showDisclaimer = false
            beginSignIn()
        }

        fun tapCancel() {
            showDisclaimer = false
        }

        private fun beginSignIn() {
            deviceFlowStarts++
        }
    }

    @Test
    fun `tapping sign in on copilot shows the disclaimer and starts nothing`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn()
        assertTrue("the disclaimer must be shown", g.showDisclaimer)
        assertEquals("Device Flow must NOT have started", 0, g.deviceFlowStarts)
    }

    @Test
    fun `cancelling returns without ever starting device flow`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn()
        g.tapCancel()
        assertFalse(g.showDisclaimer)
        assertEquals("cancel must leave no network call behind", 0, g.deviceFlowStarts)
    }

    @Test
    fun `only accepting starts device flow, exactly once`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn()
        g.tapAccept()
        assertFalse(g.showDisclaimer)
        assertEquals(1, g.deviceFlowStarts)
    }

    @Test
    fun `cancel then accept starts exactly one flow`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn(); g.tapCancel()
        g.tapSignIn(); g.tapAccept()
        assertEquals(1, g.deviceFlowStarts)
    }

    @Test
    fun `non-copilot providers are not gated`() {
        val g = GateHarness(isCopilot = false)
        g.tapSignIn()
        assertFalse("no disclaimer for other providers", g.showDisclaimer)
        assertEquals(1, g.deviceFlowStarts)
    }
}
