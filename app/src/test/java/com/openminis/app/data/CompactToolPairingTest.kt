package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactToolPairingTest {


    private fun userText(text: String) =
        LLMMessage(role = LLMMessage.Role.USER, content = text)

    private fun assistantToolUse(vararg ids: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = ids.map { AgentContentPart.ToolUse(it, "shell_execute", JSONObject()) },
    )

    private fun toolResult(vararg ids: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = ids.map { AgentContentPart.ToolResult(it, "shell_execute", "ok") },
    )


    private fun isBoundaryEligible(msg: LLMMessage): Boolean =
        msg.role == LLMMessage.Role.USER &&
            msg.contentParts.none { it is AgentContentPart.ToolResult }

    @Test
    fun `walk-back refuses a tool-result-carrying user message as a boundary`() {
        val history = listOf(
            userText("do the thing"),
            assistantToolUse("call_A"),
            toolResult("call_A"),
        )
        assertTrue("a plain user turn starts a round", isBoundaryEligible(history[0]))
        assertTrue("an assistant turn is never a boundary", !isBoundaryEligible(history[1]))
        assertTrue(
            "a user message carrying a tool result is the SECOND half of a round; " +
                "cutting here orphans call_A's tool_use",
            !isBoundaryEligible(history[2]),
        )
    }


    private fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
        val toolUseIds = HashSet<String>()
        val toolResultIds = HashSet<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> toolUseIds.add(com.openminis.app.provider.ToolPairing.key(part.id))
                    is AgentContentPart.ToolResult -> toolResultIds.add(com.openminis.app.provider.ToolPairing.key(part.id))
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        val last = history.lastOrNull()
        if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(com.openminis.app.provider.ToolPairing.key(part.id))
            }
        }
        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(com.openminis.app.provider.ToolPairing.key(part.id)) else true
            }
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(com.openminis.app.provider.ToolPairing.key(it.id)) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = "Tool execution was interrupted by an unexpected error.",
                                isError = true,
                            )
                        },
                    ),
                )
            }
        }
        return cleaned
    }

    private fun allIds(history: List<LLMMessage>): Pair<Set<String>, Set<String>> {
        val uses = HashSet<String>()
        val results = HashSet<String>()
        for (m in history) for (p in m.contentParts) {
            if (p is AgentContentPart.ToolUse) uses.add(p.id)
            if (p is AgentContentPart.ToolResult) results.add(p.id)
        }
        return uses to results
    }

    @Test
    fun `a balanced history passes through untouched`() {
        val history = listOf(
            userText("hi"),
            assistantToolUse("call_A"),
            toolResult("call_A"),
            userText("thanks"),
        )
        assertEquals(history, dropOrphanedToolParts(history))
    }

    @Test
    fun `orphan sweep drops a result whose call was cut away`() {
        val slice = listOf(
            toolResult("call_M1ate3"),
            userText("continue"),
        )
        val repaired = dropOrphanedToolParts(slice)
        val (uses, results) = allIds(repaired)
        assertTrue("the orphaned output must not reach the provider", results.isEmpty())
        assertTrue(uses.isEmpty())
        assertEquals("the emptied message is removed, the text turn survives", 1, repaired.size)
        assertEquals("continue", repaired[0].content)
    }

    @Test
    fun `orphan sweep synthesises a result for a mid-history unanswered call`() {
        val slice = listOf(
            assistantToolUse("call_LOST"),
            userText("next question"),
        )
        val repaired = dropOrphanedToolParts(slice)
        val (uses, results) = allIds(repaired)
        assertEquals("the call is preserved, not deleted", setOf("call_LOST"), uses)
        assertEquals("and it is now paired", setOf("call_LOST"), results)
        val synthesized = repaired.first { m ->
            m.contentParts.any { it is AgentContentPart.ToolResult }
        }.contentParts.filterIsInstance<AgentContentPart.ToolResult>().first()
        assertTrue("the placeholder is flagged as an error", synthesized.isError)
    }

    @Test
    fun `in-flight trailing tool_use is exempt from the sweep`() {
        val midRound = listOf(
            userText("run it"),
            assistantToolUse("call_INFLIGHT"),
        )
        val repaired = dropOrphanedToolParts(midRound)
        assertEquals("mid-round history must pass through untouched", midRound, repaired)
        val (_, results) = allIds(repaired)
        assertTrue("no fabricated result may be injected mid-round", results.isEmpty())
    }

    @Test
    fun `exemption covers only the tail, not earlier unanswered calls`() {
        val slice = listOf(
            assistantToolUse("call_OLD"),
            userText("meanwhile"),
            assistantToolUse("call_INFLIGHT"),
        )
        val repaired = dropOrphanedToolParts(slice)
        val (uses, results) = allIds(repaired)
        assertTrue("both calls survive", uses.containsAll(setOf("call_OLD", "call_INFLIGHT")))
        assertEquals("only the stale one is paired", setOf("call_OLD"), results)
    }

    @Test
    fun `a plain text message with no parts is never dropped`() {
        val slice = listOf(
            toolResult("call_ORPHAN"),
            userText("plain text carries no contentParts and must survive"),
        )
        val repaired = dropOrphanedToolParts(slice)
        assertEquals(1, repaired.size)
        assertTrue(repaired[0].content.startsWith("plain text"))
    }


    private fun chatProvider(responses: Boolean = false) = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI"),
        basePath = "https://example.invalid/v1",
        useResponsesAPI = responses,
    )

    private fun chatMessages(history: List<LLMMessage>): JSONArray =
        chatProvider().buildRequestBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 512,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        ).getJSONArray("messages")

    private fun responsesInput(history: List<LLMMessage>): JSONArray =
        chatProvider(responses = true).buildResponsesAPIBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 512,
            stream = false,
        ).getJSONArray("input")

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    private fun assertToolPairingOnWire(msgs: List<JSONObject>) {
        var pending = mutableSetOf<String>()
        for ((i, m) in msgs.withIndex()) {
            when (m.optString("role")) {
                "assistant" -> {
                    assertTrue("assistant at $i while calls still unanswered: $pending", pending.isEmpty())
                    val calls = m.optJSONArray("tool_calls") ?: continue
                    pending = calls.objects().map { it.getString("id") }.toMutableSet()
                }
                "tool" -> {
                    val id = m.getString("tool_call_id")
                    assertTrue(
                        "role 'tool' at $i (id=$id) does not follow a tool_calls message claiming it " +
                            "— this is the `role 'tool' must follow tool_calls` 400",
                        pending.remove(id),
                    )
                }
                else -> assertTrue("message at $i interrupts an unanswered tool run: $pending", pending.isEmpty())
            }
        }
        assertTrue("history ends with unanswered calls: $pending", pending.isEmpty())
    }

    @Test
    fun `stop during a tool_use then continue leaves no dangling call on the wire`() {
        val stopped = listOf(
            userText("build it"),
            assistantToolUse("call_STOPPED"),
        )
        assertEquals(stopped, dropOrphanedToolParts(stopped))

        val continued = stopped + userText("continue")
        val repaired = dropOrphanedToolParts(continued)
        val (uses, results) = allIds(repaired)
        assertEquals(setOf("call_STOPPED"), uses)
        assertEquals("the stopped call is answered, not deleted", setOf("call_STOPPED"), results)
        assertEquals("synthesised result sits BEFORE the continuation text", 4, repaired.size)
        assertTrue(repaired[2].contentParts.any { it is AgentContentPart.ToolResult })
        assertEquals("continue", repaired[3].content)

        assertToolPairingOnWire(chatMessages(repaired).objects())
    }

    @Test
    fun `Responses API - a compacted-away function_call takes its function_call_output with it`() {
        val slice = listOf(
            toolResult("call_GONE"),
            userText("what next?"),
        )
        val rawTypes = responsesInput(slice).objects().map { it.optString("type") }
        assertFalse("the wire gate drops the orphan on its own", rawTypes.contains("function_call_output"))

        val repaired = dropOrphanedToolParts(slice)
        val items = responsesInput(repaired).objects()
        assertFalse(
            "no function_call_output may be sent without its function_call",
            items.any { it.optString("type") == "function_call_output" },
        )
        assertFalse(items.any { it.optString("type") == "function_call" })
        assertEquals("the user's text still goes", 1, items.count { it.optString("role") == "user" })
    }

    @Test
    fun `a call id reused across two assistant messages is renamed on the second`() {
        val history = listOf(
            userText("first"),
            assistantToolUse("call_dup"),
            toolResult("call_dup"),
            userText("again"),
            assistantToolUse("call_dup"),
            toolResult("call_dup"),
        )
        val msgs = chatMessages(history).objects()
        val callIds = msgs.filter { it.has("tool_calls") }
            .flatMap { it.getJSONArray("tool_calls").objects() }.map { it.getString("id") }
        val replyIds = msgs.filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") }

        assertEquals(listOf("call_dup", "call_dup-2"), callIds)
        assertEquals("replies renamed in lockstep with their claims", callIds, replyIds)
        assertEquals("ids are unique across the whole messages array", callIds.size, callIds.toSet().size)
        assertToolPairingOnWire(msgs)
    }

    @Test
    fun `an id over 64 chars is capped and the tool reply uses the same capped value`() {
        val longId = "call_" + "x".repeat(76)
        assertTrue(longId.length > 64)
        val history = listOf(userText("go"), assistantToolUse(longId), toolResult(longId))
        val msgs = chatMessages(history).objects()
        val claim = msgs.first { it.has("tool_calls") }.getJSONArray("tool_calls").getJSONObject(0).getString("id")
        val reply = msgs.first { it.optString("role") == "tool" }.getString("tool_call_id")

        assertTrue("capped claim must be <= 64 chars, got ${claim.length}", claim.length <= 64)
        assertNotEquals("the raw id must not be sent", longId, claim)
        assertTrue("capped id still reads as a call id", claim.startsWith("call_"))
        assertEquals("claim and reply must agree byte-for-byte", claim, reply)
        assertToolPairingOnWire(msgs)

        val combined = "call_abc123|fc_" + "y".repeat(60)
        val msgs2 = chatMessages(listOf(userText("go"), assistantToolUse(combined), toolResult(combined))).objects()
        val claim2 = msgs2.first { it.has("tool_calls") }.getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertEquals("call_abc123", claim2)
        assertEquals(claim2, msgs2.first { it.optString("role") == "tool" }.getString("tool_call_id"))
    }

    @Test
    fun `drift guard - production sweep and provider caps still exist`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("private fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage>"))
        assertTrue("in-flight exemption removed?", vm.contains("if (part is AgentContentPart.ToolUse) orphanedUses.remove(com.openminis.app.provider.ToolPairing.key(part.id))"))
        assertTrue("sweep no longer on the outgoing path?",
            vm.contains("dropOrphanedToolParts(input.trim(compacted(input.history, input.compaction, input.keepRecentUserTurns, compactionTrace)), input.sessionId, orphanTrace)"))
        assertTrue(vm.contains("Tool execution was interrupted by an unexpected error."))

        val oai = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(oai.contains("globallyDedupeToolCallIds(messagesArray)"))
        assertTrue(oai.contains("put(\"id\", wireIds.idFor(tu.id))"))
        assertTrue(oai.contains("put(\"tool_call_id\", wireIds.idFor(tr.id))"))
        assertTrue(oai.contains("put(\"call_id\", capResponsesId(callId))"))
    }
}
