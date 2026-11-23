package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShowStopSubAgentTest {

    @Before fun reset() = AgentJobRegistry.resetForTest()

    private fun child(parent: String = "P") = AgentJobRegistry.register(
        title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tu"), prompt = "do", then = AgentJobThen.None,
    )

    private fun showStop(isStreaming: Boolean, sessionId: String, hasContent: Boolean): Boolean {
        val busy = isStreaming || AgentJobRegistry.hasAgentWork(sessionId)
        return busy && !hasContent
    }

    @Test
    fun `stop shows while only a sub agent is running`() {
        val j = child("P")
        AgentJobRegistry.markRunning(j.id, "S-child")
        assertTrue(
            "the button must survive the parent going idle",
            showStop(isStreaming = false, sessionId = "P", hasContent = false),
        )
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "ok")
        assertFalse(
            "and go away once the work is done",
            showStop(isStreaming = false, sessionId = "P", hasContent = false),
        )
    }

    @Test
    fun `typed content still wins over stop`() {
        val j = child("P")
        AgentJobRegistry.markRunning(j.id, "S-child")
        assertFalse(
            "with text in the box the button is Send/Enqueue, not Stop",
            showStop(isStreaming = false, sessionId = "P", hasContent = true),
        )
    }

    @Test
    fun `another session's agent work does not show stop here`() {
        val j = child("Q")
        AgentJobRegistry.markRunning(j.id, "S-other")
        assertFalse(showStop(isStreaming = false, sessionId = "P", hasContent = false))
        assertTrue(showStop(isStreaming = false, sessionId = "Q", hasContent = false))
    }

    @Test
    fun `cancelling the session stops sub agents with the parent idle`() {
        val a = child("P"); val b = child("P")
        AgentJobRegistry.markRunning(a.id, "SA") {}
        AgentJobRegistry.markRunning(b.id, "SB") {}
        assertEquals(2, AgentJobRegistry.activeChildren("P").size)

        AgentJobRegistry.muteDelegationResults("P")
        AgentJobRegistry.dropQueuedDelegations("P", "user-stopped")
        AgentJobRegistry.cancelAll("P", "user-stopped")

        assertTrue(
            "pressing Stop must really stop them",
            AgentJobRegistry.activeChildren("P").isEmpty(),
        )
        assertFalse(
            "and the button must then disappear",
            showStop(isStreaming = false, sessionId = "P", hasContent = false),
        )
    }
}
