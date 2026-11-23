package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GPT6SolLunaOpus55Test {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun codex(id: String): LLMModel =
        OpenAIModelsApi.fetchModelsOAuth().first { it.id == id }

    private val opus55: LLMModel get() = LLMModel.claudeOpus55


    @Test
    fun `the new codex entries are shipped in the oauth list`() {
        val ids = OpenAIModelsApi.fetchModelsOAuth().map { it.id }
        assertTrue("gpt-6-sol missing from the Codex OAuth list", ids.contains("gpt-6-sol"))
        assertTrue("gpt-6-luna missing from the Codex OAuth list", ids.contains("gpt-6-luna"))
    }

    @Test
    fun `the new codex entries declare supportsReasoning`() {
        for (id in listOf("gpt-6-sol", "gpt-6-luna")) {
            assertEquals(
                "$id must declare supportsReasoning=true in the static Codex OAuth list — " +
                    "neither id is in the bundled models.dev snapshot, so nothing fills " +
                    "it in later",
                true,
                codex(id).supportsReasoning,
            )
        }
    }

    @Test
    fun `opus 5 5 is shipped in the anthropic list and declares supportsReasoning`() {
        assertTrue(
            "claude-opus-5-5 missing from allAnthropic",
            LLMModel.allAnthropic.any { it.id == "claude-opus-5-5" },
        )
        assertEquals(true, opus55.supportsReasoning)
        assertEquals(1_000_000, opus55.contextWindow)
        assertEquals(128_000, opus55.maxOutputTokens)
    }


    @Test
    fun `catalog ceiling is max for all three`() {
        assertEquals(ThinkingLevel.MAX, codex("gpt-6-sol").catalogMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, codex("gpt-6-luna").catalogMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, opus55.catalogMaxThinkingLevel)
    }

    @Test
    fun `opus 5 5 is not left on the conservative default`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-5-5"))
        assertNotEquals(ThinkingLevel.XHIGH, opus55.catalogMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-5-6"))
    }

    @Test
    fun `an undeclared flag still reaches max rather than collapsing to off`() {
        for (id in listOf("gpt-6-sol", "gpt-6-luna")) {
            val m = codex(id)
            assertEquals(ThinkingLevel.MAX, m.copy(supportsReasoning = null).catalogMaxThinkingLevel)
            assertEquals(ThinkingLevel.OFF, m.copy(supportsReasoning = false).catalogMaxThinkingLevel)
        }
    }


    private fun responsesBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "question")),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun effortOf(body: JSONObject): String? =
        body.optJSONObject("reasoning")?.optString("effort")?.takeIf { it.isNotEmpty() }

    @Test
    fun `every thinking level reaches the codex wire as itself`() {
        val expected = mapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high",
            ThinkingLevel.XHIGH to "xhigh",
            ThinkingLevel.MAX to "max",
            ThinkingLevel.ULTRA to "max",
        )
        for (id in listOf("gpt-6-sol", "gpt-6-luna")) {
            val model = codex(id)
            for ((level, wire) in expected) {
                assertEquals(
                    "$id at $level must put effort=$wire on the wire",
                    wire,
                    effortOf(responsesBody(model, level)),
                )
            }
        }
    }


    private fun anthropicBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msg_1","type":"message","role":"assistant","content":[],""" +
                    """"model":"${model.id}","stop_reason":"end_turn",""" +
                    """"usage":{"input_tokens":1,"output_tokens":1}}""",
            ),
        )
        val provider = AnthropicProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/").toString().trimEnd('/'),
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "question")),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `opus 5 5 off is adaptive at low effort - never the literal thinking disabled`() {
        assertEquals(AnthropicProvider.OffThinking.LOW_EFFORT, AnthropicProvider.offThinking("claude-opus-5-5"))
        assertEquals(AnthropicProvider.OffThinking.DISABLED, AnthropicProvider.offThinking("claude-opus-4-8"))

        val body = anthropicBody(opus55, ThinkingLevel.OFF)
        val thinking = body.optJSONObject("thinking")
        assertNotEquals(
            "OFF on Opus 5.5 must not put thinking.type=disabled on the wire — the " +
                "model 400s on it",
            "disabled",
            thinking?.optString("type"),
        )
        assertEquals("adaptive", thinking?.optString("type"))
        assertEquals("summarized", thinking?.optString("display"))
        assertEquals("low", body.optJSONObject("output_config")?.optString("effort"))
    }

    @Test
    fun `opus 5 5 uses adaptive thinking with an effort tier`() {
        assertTrue(AnthropicProvider.modelUsesAdaptiveThinking("claude-opus-5-5"))
        val body = anthropicBody(opus55, ThinkingLevel.MAX)
        val thinking = body.optJSONObject("thinking")
        assertNotNull("adaptive thinking must be present at MAX", thinking)
        assertEquals("adaptive", thinking!!.optString("type"))
        assertEquals(
            "MAX must reach the wire as effort=max",
            "max",
            body.optJSONObject("output_config")?.optString("effort"),
        )
    }

    @Test
    fun `opus 5 5 does not send temperature`() {
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-opus-5-5"))
    }
}
