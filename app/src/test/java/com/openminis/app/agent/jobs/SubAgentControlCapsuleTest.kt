package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentControlCapsuleTest {

    private fun args(action: String) = """{"action":"$action","tool_title":"t"}"""

    @Test
    fun `a delegation is not a control call`() {
        assertFalse(HelperRunner.isControlOnly(args("delegate"), null))
        assertFalse(HelperRunner.isControlOnly("""{"tool_title":"t"}""", null))
    }

    @Test
    fun `every non-delegate action is a control call`() {
        for (a in listOf("status", "steer", "cancel", "resume")) {
            assertTrue("$a must classify as control", HelperRunner.isControlOnly(args(a), null))
        }
    }

    @Test
    fun `each action is classified as itself`() {
        assertEquals(HelperRunner.SubAgentCall.DELEGATE, HelperRunner.classifyCall(args("delegate"), null))
        assertEquals(HelperRunner.SubAgentCall.STATUS, HelperRunner.classifyCall(args("status"), null))
        assertEquals(HelperRunner.SubAgentCall.STEER, HelperRunner.classifyCall(args("steer"), null))
        assertEquals(HelperRunner.SubAgentCall.CANCEL, HelperRunner.classifyCall(args("cancel"), null))
        assertEquals(HelperRunner.SubAgentCall.RESUME, HelperRunner.classifyCall(args("resume"), null))
    }

    @Test
    fun `a steer is recognised from its result when the arguments are gone`() {
        val steerResult = """{"ok":true,"status":"queued","job_id":"abc12345",""" +
            """"detail":"Queued. The sub agent reads it at its next turn."}"""
        assertEquals(HelperRunner.SubAgentCall.STEER, HelperRunner.classifyCall(null, steerResult))
        assertTrue(
            "a steer must be control-only, or the queue-loss check will badge it Never started",
            HelperRunner.isControlOnly(null, steerResult),
        )
    }

    @Test
    fun `a queued delegation remains a delegation`() {
        val delegateQueued = """{"ok":true,"status":"queued","queued_behind":2,""" +
            """"detail":"All 3 slots are busy. This task is QUEUED and will start automatically."}"""
        assertFalse(HelperRunner.isControlOnly(args("delegate"), delegateQueued))
    }

    @Test
    fun `garbage arguments do not make a block a control call`() {
        assertFalse(HelperRunner.isControlOnly("not json", null))
        assertFalse(HelperRunner.isControlOnly(null, null))
    }
}
