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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenRouterCacheControlTest {

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

    private fun model(id: String) =
        LLMModel(id = id, displayName = id, provider = "OpenRouter")

    private fun capture(basePath: String, model: LLMModel): JSONObject {
        val ok =
            """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
            )
        }
        val provider = OpenAIProvider(apiKey = "test-key", model = model, basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                    systemPrompt = null,
                    maxTokens = 1024,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.OFF,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun openRouter() = server.url("/openrouter.ai/api/v1").toString().trimEnd('/')


    @Test
    fun `claude on openrouter carries the ephemeral cache_control breakpoint`() {
        val body = capture(openRouter(), model("anthropic/claude-sonnet-4.5"))
        assertTrue(
            "Claude on OpenRouter must opt into prompt caching (GH#191): $body",
            body.has("cache_control"),
        )
        assertEquals("ephemeral", body.getJSONObject("cache_control").getString("type"))
    }


    @Test
    fun `non-anthropic models on openrouter keep a byte-identical body`() {
        val body = capture(openRouter(), model("openai/gpt-4o-mini"))
        assertFalse(
            "cache_control must not leak onto non-Anthropic OpenRouter models: $body",
            body.has("cache_control"),
        )
    }


    @Test
    fun `anthropic-prefixed model off openrouter does not get the field`() {
        val body = capture(
            server.url("/api.example-relay.com/v1").toString().trimEnd('/'),
            model("anthropic/claude-sonnet-4.5"),
        )
        assertFalse(
            "cache_control is scoped to the OpenRouter host: $body",
            body.has("cache_control"),
        )
    }

    @Test
    fun `mistral does not receive cache_control`() {
        val body = capture(
            server.url("/mistral.ai/v1").toString().trimEnd('/'),
            model("mistral-large-latest"),
        )
        assertFalse("Mistral must never receive cache_control: $body", body.has("cache_control"))
    }


    @Test
    fun `an uppercase anthropic prefix still opts in`() {
        val body = capture(openRouter(), model("Anthropic/Claude-Sonnet-4.5"))
        assertTrue(
            "prefix match must be case-insensitive: $body",
            body.has("cache_control"),
        )
    }
}
