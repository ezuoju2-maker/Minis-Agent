package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentBudgetPolicyTest {

    @Test
    fun `default turn budget is narrower than the main loop`() {
        assertEquals(60, SubAgentBudgetPolicy.DEFAULT_TURNS)
        assertTrue(SubAgentBudgetPolicy.DEFAULT_TURNS < AgentLoopLimits.MAX_AGENT_TURNS)
    }

    @Test
    fun `requested turns are clamped into the allowed range`() {
        assertEquals(60, SubAgentBudgetPolicy.coerceTurns(null))
        assertEquals(8, SubAgentBudgetPolicy.coerceTurns(5))
        assertEquals(8, SubAgentBudgetPolicy.coerceTurns(8))
        assertEquals(120, SubAgentBudgetPolicy.coerceTurns(120))
        assertEquals(200, SubAgentBudgetPolicy.coerceTurns(500))
    }

    @Test
    fun `warning arrives early enough to finish writing`() {
        assertEquals(12, SubAgentBudgetPolicy.warningLead(60))
        assertEquals(3, SubAgentBudgetPolicy.warningLead(10))
        assertEquals(3, SubAgentBudgetPolicy.warningLead(4))
    }

    @Test
    fun `warning window only covers the tail of the budget`() {
        assertTrue(SubAgentBudgetPolicy.shouldWarn(12, 60))
        assertTrue(SubAgentBudgetPolicy.shouldWarn(1, 60))
        assertFalse(SubAgentBudgetPolicy.shouldWarn(13, 60))
        assertFalse(SubAgentBudgetPolicy.shouldWarn(0, 60))
    }
}
