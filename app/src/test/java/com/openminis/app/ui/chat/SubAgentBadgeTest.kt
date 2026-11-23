package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.HelperModelTier
import com.openminis.app.agent.jobs.HelperRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAgentBadgeTest {

    private fun block(json: String) = AssistantBlock(
        id = "b1", kind = "tool_use", content = json,
        toolStatus = ToolBlockStatus.SUCCESS, toolName = HelperRunner.TOOL_NAME,
    )

    @Test
    fun `a finished run carries the sub agent's name`() {
        val json = HelperRunner.resultJson(
            "completed", "body", "GPT", HelperModelTier.PRIMARY, "primary", 2, 1_000, "C", "J",
            summary = "", agentName = "svg-drawing-agent",
        )
        assertEquals("svg-drawing-agent", parseHelperBlock(block(json)).agent)
    }

    @Test
    fun `the built-in agent writes no name and keeps the generic label`() {
        val json = HelperRunner.resultJson(
            "completed", "body", "GPT", HelperModelTier.PRIMARY, "primary", 2, 1_000, "C", "J",
            summary = "", agentName = null,
        )
        assertNull(parseHelperBlock(block(json)).agent)
    }

    @Test
    fun `a running payload carries no name`() {
        val running = HelperRunner.progressJson("C", "title", "browser_use", "working", 5_000)
        val info = parseHelperBlock(
            AssistantBlock(
                id = "b2", kind = "tool_use", content = running,
                toolStatus = ToolBlockStatus.RUNNING, toolName = HelperRunner.TOOL_NAME,
            ),
        )
        assertNull("progressJson has no agent key — the badge stays generic", info.agent)
    }
}
