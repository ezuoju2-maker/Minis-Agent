package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiParallelThoughtSignatureTest {

    private fun toolUse(id: String, sig: String?) =
        AgentContentPart.ToolUse(id, "subagent_task", JSONObject(), thoughtSignature = sig)

    private fun unsigned(messages: List<LLMMessage>): Set<String> = buildSet {
        for (msg in messages) {
            val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (toolUses.isEmpty()) continue
            if (toolUses.any { it.thoughtSignature.isNullOrEmpty() }) {
                toolUses.forEach { add(it.id) }
            }
        }
    }

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    @Test
    fun `a parallel batch with one signature is downgraded whole`() {
        val msg = assistant(
            toolUse("c1", "SIG".repeat(1365)),
            toolUse("c2", null),
            toolUse("c3", null),
            toolUse("c4", null),
            toolUse("c5", null),
        )
        val u = unsigned(listOf(msg))
        assertEquals(
            "the signed call must go down with its batch, not be replayed alone",
            setOf("c1", "c2", "c3", "c4", "c5"), u,
        )
    }

    @Test
    fun `a fully signed batch is replayed as function calls`() {
        val msg = assistant(toolUse("c1", "sig-a"), toolUse("c2", "sig-b"))
        assertTrue("nothing may be downgraded when every call is signed", unsigned(listOf(msg)).isEmpty())
    }

    @Test
    fun `a lone call is judged on its own signature`() {
        assertTrue(unsigned(listOf(assistant(toolUse("c1", "sig")))).isEmpty())
        assertEquals(setOf("c1"), unsigned(listOf(assistant(toolUse("c1", null)))))
    }

    @Test
    fun `an unsigned turn does not condemn a signed one`() {
        val old = assistant(toolUse("old1", null), toolUse("old2", null))
        val new = assistant(toolUse("new1", "sig-a"), toolUse("new2", "sig-b"))
        assertEquals(setOf("old1", "old2"), unsigned(listOf(old, new)))
    }

    @Test
    fun `an empty signature counts as unsigned`() {
        assertEquals(setOf("c1", "c2"), unsigned(listOf(assistant(toolUse("c1", ""), toolUse("c2", "sig")))))
    }

    @Test
    fun `a text-only message is ignored`() {
        val textOnly = assistant(AgentContentPart.Text("hello"))
        assertTrue(unsigned(listOf(textOnly)).isEmpty())
    }
}
