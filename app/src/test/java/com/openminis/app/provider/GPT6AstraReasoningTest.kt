package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GPT6AstraReasoningTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private val astra: LLMModel
        get() = OpenAIModelsApi.fetchModelsOAuth().first { it.id == "gpt-6-astra" }

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
    fun `the shipped codex oauth entry declares supportsReasoning`() {
        assertEquals(
            "gpt-6-astra must declare supportsReasoning=true in the static Codex OAuth " +
                "list — without it the Responses builder falls back to effort 'low' and " +
                "silently ignores the user's thinking level (iOS 682029976)",
            true,
            astra.supportsReasoning,
        )
    }

    @Test
    fun `catalog ceiling is max`() {
        assertEquals(ThinkingLevel.MAX, astra.catalogMaxThinkingLevel)
    }

    @Test
    fun `an undeclared flag still reaches max rather than collapsing to off`() {
        val undeclared = astra.copy(supportsReasoning = null)
        assertNotEquals(
            "a null supportsReasoning must NOT collapse the ceiling to OFF — that was " +
                "the iOS failure mode (?? false); Android's guard is `== false` by design",
            ThinkingLevel.OFF,
            undeclared.catalogMaxThinkingLevel,
        )
        assertEquals(ThinkingLevel.MAX, undeclared.catalogMaxThinkingLevel)
        assertEquals(
            ThinkingLevel.OFF,
            astra.copy(supportsReasoning = false).catalogMaxThinkingLevel,
        )
    }

    @Test
    fun `every thinking level maps to its own wire effort`() {
        val expected = mapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high",
            ThinkingLevel.XHIGH to "xhigh",
            ThinkingLevel.MAX to "max",
            ThinkingLevel.ULTRA to "max",
        )
        for ((level, wire) in expected) {
            val body = responsesBody(astra, level)
            assertEquals(
                "gpt-6-astra at $level must send effort '$wire': $body",
                wire,
                effortOf(body),
            )
        }
    }

    @Test
    fun `xhigh does not collapse to low`() {
        val body = responsesBody(astra, ThinkingLevel.XHIGH)
        assertNotEquals(
            "the reported bug: user picked xhigh, wire carried 'low': $body",
            "low",
            effortOf(body),
        )
        assertEquals("xhigh", effortOf(body))
    }

    @Test
    fun `the enriched device shape also reaches max and passes xhigh through`() {
        val enriched = astra.copy(
            reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
        )
        assertEquals(
            "declared tiers must put the ceiling at MAX",
            ThinkingLevel.MAX,
            enriched.catalogMaxThinkingLevel,
        )
        assertEquals(
            "every declared tier must be individually selectable",
            listOf(
                ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH,
                ThinkingLevel.XHIGH, ThinkingLevel.MAX,
            ),
            enriched.selectableThinkingLevels,
        )
        assertEquals("xhigh", effortOf(responsesBody(enriched, ThinkingLevel.XHIGH)))
        assertEquals("max", effortOf(responsesBody(enriched, ThinkingLevel.ULTRA)))
    }

    @Test
    fun `a non-reasoning model sends no reasoning block on this path`() {
        val body = responsesBody(astra.copy(supportsReasoning = false), ThinkingLevel.XHIGH)
        assertNull(
            "clampThinkingLevel drives a non-reasoning model to OFF, and the API-key " +
                "Responses path omits the reasoning object entirely: $body",
            effortOf(body),
        )
    }
}
