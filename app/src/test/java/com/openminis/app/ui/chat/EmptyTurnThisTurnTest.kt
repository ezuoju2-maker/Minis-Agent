package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyTurnThisTurnTest {

    private fun text(id: String, s: String) = AssistantBlock(id = id, kind = "text", content = s)
    private fun tool(id: String) = AssistantBlock(id = id, kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS)
    private fun thinking(id: String) = AssistantBlock(id = id, kind = "thinking", content = "plan the next part")

    private val earlier = listOf(text("t0", "继续写第二部分"), tool("u1"), tool("u2"), thinking("k1"))

    @Test
    fun `a reasoning-only stop after earlier turns is empty`() {
        val blocks = earlier + thinking("k2")
        assertFalse(EmptyTurnRecovery.turnHasText("", blocks, turnStartBlockIndex = earlier.size))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "stop"))
    }

    @Test
    fun `a final summary after earlier tool calls is not empty`() {
        assertTrue(EmptyTurnRecovery.turnHasText("全部完成，计划表已写入 docs/plan。", earlier, earlier.size))
        val blocks = earlier + text("t9", "Done.")
        assertTrue(EmptyTurnRecovery.turnHasText("", blocks, earlier.size))
    }

    @Test
    fun `whitespace is not text, and earlier text is not this turn's`() {
        assertFalse(EmptyTurnRecovery.turnHasText("  \n", earlier + text("t9", "   "), earlier.size))
        assertTrue("the whole bubble would have said yes", EmptyTurnRecovery.turnHasText("", earlier, 0))
    }

    @Test
    fun `an out-of-range start index is safe`() {
        assertFalse(EmptyTurnRecovery.turnHasText("", earlier, earlier.size + 5))
        assertTrue(EmptyTurnRecovery.turnHasText("", earlier, -1))
    }


    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    private val noToolExit: String by lazy {
        val start = vm.indexOf("runAgentLoop turn=\$turn no tool calls → break")
        assertTrue(start > 0)
        vm.substring(start, vm.indexOf("// Auto-title after first exchange", start))
    }

    @Test
    fun `the bubble-wide check is gone and both gates use this turn`() {
        assertFalse(noToolExit.contains("val hasVisibleContent"))
        assertTrue(noToolExit.contains("val turnHasText = EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex)"))
        assertTrue(noToolExit.contains("hasText = turnHasText || deferredAskShown,"))
        assertTrue("a partial reply is one THIS turn wrote",
            noToolExit.contains("if (turnFinishReason == null && turnHasText && !closingRequestDue) {"))
    }

    @Test
    fun `still empty after the reminder retry shows the hint and offers Resume`() {
        val hint = noToolExit.substring(noToolExit.indexOf("if (!closingRequestDue) {\n                        val hint"))
        val end = hint.indexOf("// [T-subagent-termination] The one-shot closing request")
        val branch = hint.substring(0, end)
        assertTrue(branch.contains("setInlineError(hint)"))
        assertTrue(branch.contains("_canResume.value = true"))
        assertTrue(branch.contains("markLiveInterruption()"))
        assertEquals("the reminder retry still runs first", 1,
            Regex("EmptyTurnRecovery.plan\\(agentHistory, didInjectEmptyToolReminder\\)").findAll(noToolExit).count())
    }
}
