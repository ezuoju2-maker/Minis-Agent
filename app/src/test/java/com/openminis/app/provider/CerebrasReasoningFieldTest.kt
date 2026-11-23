package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CerebrasReasoningFieldTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val reasoningModel = LLMModel(
        id = "qwen-3.8-27b",
        displayName = "Qwen 3.8 27B",
        provider = "Cerebras",
        supportsReasoning = true,
    )

    private fun historyWithReasoning(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "first question"),
        LLMMessage(
            LLMMessage.Role.ASSISTANT,
            "first answer",
        ).copy(reasoningContent = "some captured chain of thought"),
        LLMMessage(LLMMessage.Role.USER, "second question"),
    )

    private fun capture(basePath: String, model: LLMModel = reasoningModel): JSONObject {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(ok),
            )
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = basePath,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = historyWithReasoning(),
                    systemPrompt = null,
                    maxTokens = 1024,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.MEDIUM,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun anyMessageHasReasoning(body: JSONObject): Boolean {
        val msgs = body.getJSONArray("messages")
        for (i in 0 until msgs.length()) {
            if (msgs.getJSONObject(i).has("reasoning_content")) return true
        }
        return false
    }

    @Test
    fun `cerebras endpoint never sends reasoning_content`() {
        val body = capture(server.url("/cerebras.ai/v1").toString().trimEnd('/'))
        assertFalse(
            "reasoning_content must not be sent to Cerebras (400 unsupported): $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `cerebras detection is case-insensitive`() {
        val body = capture(server.url("/API.CEREBRAS.AI/v1").toString().trimEnd('/'))
        assertFalse(
            "uppercase cerebras.ai must still suppress reasoning_content: $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `cerebras hosted qwen does not receive enable_thinking`() {
        val body = capture(server.url("/cerebras.ai/v1").toString().trimEnd('/'))
        assertFalse(
            "enable_thinking must not be sent to Cerebras: $body",
            body.has("enable_thinking"),
        )
        assertFalse(
            "thinking_budget must not be sent to Cerebras: $body",
            body.has("thinking_budget"),
        )
        assertFalse(
            "the DashScope extra_body envelope must not be sent to Cerebras: $body",
            body.has("extra_body"),
        )
    }

    @Test
    fun `non-cerebras endpoint still echoes reasoning_content`() {
        val body = capture(server.url("/v1").toString().trimEnd('/'))
        assertTrue(
            "reasoning_content should still be echoed for non-Cerebras vendors: $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `non-cerebras qwen keeps its native thinking mechanism`() {
        val body = capture(server.url("/v1").toString().trimEnd('/'))
        assertTrue(
            "a non-Cerebras qwen should still get its native thinking field: $body",
            body.has("enable_thinking") || body.has("extra_body"),
        )
    }
}
