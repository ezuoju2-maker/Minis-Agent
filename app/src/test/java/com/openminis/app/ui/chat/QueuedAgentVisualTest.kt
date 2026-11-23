package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import org.junit.After
import org.junit.Before
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedAgentVisualTest {

    @Before
    fun enqueue() {
        AgentJobRegistry.dropQueuedDelegations("parent", "test-setup")
        AgentJobRegistry.enqueueDelegation(
            AgentJobRegistry.QueuedDelegation(
                parentSessionId = "parent", argsJson = "{}", toolUseId = "b1",
            ),
        )
    }

    @After
    fun cleanup() {
        AgentJobRegistry.dropQueuedDelegations("parent", "test-teardown")
    }

    private fun block(json: String) = AssistantBlock(
        id = "b1", kind = "tool_use", content = json,
        toolName = HelperRunner.TOOL_NAME, toolTitle = "调研 2025 苹果发布与销售",
    )

    @Test
    fun `a queued delegation parses as the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"queued","queued_behind":2,"detail":"All 3 slots are busy."}"""),
        )
        val phase = info.phase
        assertTrue("expected Queued, got $phase", phase is HelperPhase.Queued)
        assertEquals(2, (phase as HelperPhase.Queued).behind)
    }

    @Test
    fun `a starting delegation is not the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"starting","child_session_id":"c1"}"""),
        )
        assertTrue("starting must not read as queued", info.phase !is HelperPhase.Queued)
    }

    @Test
    fun `a running delegation is not the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"running","child_session_id":"c1","tool":"shell_execute"}"""),
        )
        assertTrue("running must not read as queued", info.phase !is HelperPhase.Queued)
    }

    @Test
    fun `a finished delegation is not the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"completed","result":"done","child_session_id":"c1"}"""),
        )
        assertTrue("completed must not read as queued", info.phase is HelperPhase.Finished)
    }

    @Test
    fun `a queued payload without a position still parses as queued`() {
        val info = parseHelperBlock(block("""{"ok":true,"status":"queued"}"""))
        assertTrue(info.phase is HelperPhase.Queued)
        assertEquals(0, (info.phase as HelperPhase.Queued).behind)
    }
}
