package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedTailDetectorTest {

    private fun user(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    private fun text(s: String) = AgentContentPart.Text(s)

    private fun toolResult(id: String = "t1") =
        AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "ok")

    private fun toolUse(id: String = "t1") =
        AgentContentPart.ToolUse(id = id, name = "shell_execute", input = JSONObject())


    @Test
    fun `plain text user tail with no reply is recoverable`() {
        assertEquals(
            InterruptedTailShape.UNANSWERED_USER_TURN,
            InterruptedTailDetector.classify(user(text("what is 2+2?"))),
        )
    }

    @Test
    fun `multi part user tail with text is still an unanswered turn`() {
        val m = user(text("look at this"), text("and this"))
        assertEquals(InterruptedTailShape.UNANSWERED_USER_TURN, InterruptedTailDetector.classify(m))
    }

    @Test
    fun `empty user tail is not recoverable`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(user()))
        assertFalse(InterruptedTailDetector.isInterrupted(user()))
    }


    @Test
    fun `all tool results tail is case A, not the new case`() {
        val m = user(toolResult("a"), toolResult("b"))
        assertEquals(InterruptedTailShape.TOOL_RESULT_TAIL, InterruptedTailDetector.classify(m))
    }

    @Test
    fun `continue reminder tail is case C, not the new case`() {
        val m = user(text("<system-reminder>${InterruptedTailDetector.CONTINUE_REMINDER_MARKER} but now wants to continue.</system-reminder>"))
        assertEquals(InterruptedTailShape.CONTINUE_REMINDER, InterruptedTailDetector.classify(m))
    }

    @Test
    fun `assistant tail requesting tools is case B`() {
        assertEquals(
            InterruptedTailShape.ASSISTANT_TOOL_USE,
            InterruptedTailDetector.classify(assistant(text("running…"), toolUse())),
        )
    }

    @Test
    fun `tool result tail is classified before the unanswered fallback`() {
        val m = user(toolResult())
        assertEquals(InterruptedTailShape.TOOL_RESULT_TAIL, InterruptedTailDetector.classify(m))
    }


    @Test
    fun `plain assistant reply is not interrupted`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(assistant(text("4"))))
        assertFalse(InterruptedTailDetector.isInterrupted(assistant(text("4"))))
    }

    @Test
    fun `empty history is not interrupted`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(null))
        assertFalse(InterruptedTailDetector.isInterrupted(null))
    }

    @Test
    fun `mixed user tail with a tool result and text is an unanswered turn`() {
        val m = user(toolResult(), text("also, explain why"))
        assertEquals(InterruptedTailShape.UNANSWERED_USER_TURN, InterruptedTailDetector.classify(m))
    }

    @Test
    fun `isInterrupted agrees with classify for every shape`() {
        assertTrue(InterruptedTailDetector.isInterrupted(user(text("hi"))))
        assertTrue(InterruptedTailDetector.isInterrupted(user(toolResult())))
        assertTrue(InterruptedTailDetector.isInterrupted(assistant(toolUse())))
        assertFalse(InterruptedTailDetector.isInterrupted(assistant(text("done"))))
    }
}
