package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexOAuthNoSyntheticReasoningIdTest {

    private fun codexOAuthProvider(): OpenAIProvider = OpenAIProvider(
        oauthTokenProvider = { "test-oauth-token" },
        model = LLMModel(
            id = "gpt-5.6-sol",
            displayName = "GPT-5.6 Sol",
            provider = "OpenAI",
        ),
        codexAccountId = "acct_test",
    )

    private fun toolTurnWithoutReasoningEcho(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "list the files"),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    id = "call_abc123def456ghi789jkl012",
                    name = "shell_execute",
                    input = JSONObject().put("command", "ls"),
                ),
            ),
        ),
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "call_abc123def456ghi789jkl012",
                    name = "shell_execute",
                    content = "a.txt\nb.txt",
                ),
            ),
        ),
        LLMMessage(LLMMessage.Role.USER, "now summarize them"),
    )

    private fun buildBody(): JSONObject = codexOAuthProvider().buildResponsesAPIBody(
        messages = toolTurnWithoutReasoningEcho(),
        systemPrompt = "you are a helpful agent",
        maxTokens = 4096,
        stream = true,
        imageParts = emptyList(),
        tools = listOf(
            AgentToolDefinition(
                name = "shell_execute",
                description = "run a shell command",
                parameters = mapOf(
                    "command" to AgentToolParam(type = "string", description = "the command"),
                ),
                required = listOf("command"),
            ),
        ),
        thinkingLevel = ThinkingLevel.MEDIUM,
    )

    private fun inputItems(body: JSONObject): List<JSONObject> {
        val input: JSONArray? = body.optJSONArray("input")
        assertNotNull("request has no `input` array: $body", input)
        return (0 until input!!.length()).map { input.getJSONObject(it) }
    }

    @Test
    fun `no input item carries a synthetic rs_ reasoning id`() {
        val body = buildBody()
        val offenders = inputItems(body).filter { item ->
            val id = item.optString("id", "")
            id.startsWith("rs_")
        }
        assertTrue(
            "An `rs_…` id in the request is a reference into server-side storage " +
                "that cannot exist under store:false — this is the iOS 04c80e68e 404. " +
                "Offending items: $offenders",
            offenders.isEmpty(),
        )
        assertFalse(
            "request body contains a synthesized rs_syn_ id: $body",
            body.toString().contains("rs_syn_"),
        )
    }

    @Test
    fun `a tool turn with no reasoning echo emits no reasoning item at all`() {
        val reasoningItems = inputItems(buildBody()).filter { it.optString("type") == "reasoning" }
        assertTrue(
            "Android does not capture an `rs_` id to replay, so it must not emit a " +
                "reasoning item here; emitting one would require an id it cannot " +
                "legitimately produce. Found: $reasoningItems",
            reasoningItems.isEmpty(),
        )
    }

    @Test
    fun `store stays false, which is what makes a fabricated id fatal`() {
        assertEquals(
            "Responses requests are expected to send store:false",
            false,
            buildBody().opt("store"),
        )
    }

    @Test
    fun `the function_call label is still synthesized when history lacks one`() {
        val calls = inputItems(buildBody()).filter { it.optString("type") == "function_call" }
        assertEquals("expected exactly one function_call item", 1, calls.size)
        val call = calls.single()
        assertEquals("call_abc123def456ghi789jkl012", call.optString("call_id"))
        assertTrue(
            "history with no fc_ id must still get a deterministic label, got: ${call.optString("id")}",
            call.optString("id").startsWith("fc_syn_"),
        )
    }

    @Test
    fun `the synthetic function_call label is deterministic across rebuilds`() {
        fun fcIds() = inputItems(buildBody())
            .filter { it.optString("type") == "function_call" }
            .map { it.optString("id") }
        assertEquals(fcIds(), fcIds())
    }
}
