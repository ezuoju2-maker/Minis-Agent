package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CopilotReasoningStreamTest {

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

    private fun model(id: String = "claude-sonnet-5") = LLMModel(
        id = id,
        displayName = id,
        provider = "GitHub Copilot",
        supportsReasoning = true,
        reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
    )

    private fun stream(vararg dataLines: String): List<LLMStreamChunk> {
        val body = buildString {
            for (line in dataLines) {
                appendLine("data: $line")
                appendLine()
            }
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(
            MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"),
        )
        val provider = OpenAIProvider(
            apiKey = "k",
            model = model(),
            basePath = server.url("/api.githubcopilot.com").toString().trimEnd('/'),
        )
        return runBlocking {
            provider.streamMessage(
                listOf(LLMMessage(LLMMessage.Role.USER, "why?")),
                null,
                1024,
            ).toList()
        }
    }

    private fun thinking(chunks: List<LLMStreamChunk>) =
        chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }

    private fun text(chunks: List<LLMStreamChunk>) =
        chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }


    @Test
    fun `reasoning_text deltas surface as thinking`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"role":"assistant","reasoning_text":" this is a"}}]}""",
            """{"choices":[{"delta":{"content":null,"reasoning_text":" standard mod"}}]}""",
            """{"choices":[{"delta":{"content":"Here is the answer."}}]}""",
        )
        assertEquals(
            "both reasoning_text deltas must reach the thinking stream",
            " this is a standard mod",
            thinking(chunks),
        )
        assertEquals("…and the visible answer is unaffected", "Here is the answer.", text(chunks))
    }

    @Test
    fun `a long run of reasoning_text deltas concatenates in order`() {
        val words = (1..40).map { "w$it " }
        val chunks = stream(
            *words.map { """{"choices":[{"delta":{"content":null,"reasoning_text":"$it"}}]}""" }.toTypedArray(),
        )
        assertEquals(words.joinToString(""), thinking(chunks))
    }


    @Test
    fun `the two older spellings are still parsed`() {
        assertEquals(
            "deepseek/kimi spelling",
            "thought A",
            thinking(stream("""{"choices":[{"delta":{"reasoning_content":"thought A"}}]}""")),
        )
        assertEquals(
            "relay spelling",
            "thought B",
            thinking(stream("""{"choices":[{"delta":{"reasoning":"thought B"}}]}""")),
        )
    }

    @Test
    fun `a delta carrying several spellings yields the text once, by precedence`() {
        assertEquals(
            "reasoning_content wins over the others",
            "first",
            thinking(
                stream(
                    """{"choices":[{"delta":{"reasoning_content":"first","reasoning":"second","reasoning_text":"third"}}]}""",
                ),
            ),
        )
        assertEquals(
            "an EMPTY earlier spelling falls through to the next",
            "second",
            thinking(
                stream("""{"choices":[{"delta":{"reasoning_content":"","reasoning":"second"}}]}"""),
            ),
        )
        assertEquals(
            "…and through both, to reasoning_text",
            "third",
            thinking(
                stream("""{"choices":[{"delta":{"reasoning_content":"","reasoning":"","reasoning_text":"third"}}]}"""),
            ),
        )
    }

    @Test
    fun `an empty reasoning_text emits nothing but is not an error`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"reasoning_text":""}}]}""",
            """{"choices":[{"delta":{"content":"answer"}}]}""",
        )
        assertEquals("no empty thinking delta", "", thinking(chunks))
        assertEquals("answer", text(chunks))
        assertTrue("the turn must still finish cleanly", chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `a null reasoning field never becomes the literal null`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"reasoning_text":null}}]}""",
            """{"choices":[{"delta":{"content":"answer"}}]}""",
        )
        assertEquals("", thinking(chunks))
        assertEquals("answer", text(chunks))
    }

    @Test
    fun `interleaved reasoning and content stay in separate streams`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"reasoning_text":"think1 "}}]}""",
            """{"choices":[{"delta":{"content":"answer1 "}}]}""",
            """{"choices":[{"delta":{"content":null,"reasoning_text":"think2"}}]}""",
            """{"choices":[{"delta":{"content":"answer2"}}]}""",
        )
        assertEquals("think1 think2", thinking(chunks))
        assertEquals("answer1 answer2", text(chunks))
    }


    @Test
    fun `the chat-completions branch still knows all three reasoning spellings`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        for (needle in listOf(
            """d.safeOptString("reasoning_content", "")""",
            """.ifEmpty { d.safeOptString("reasoning", "") }""",
            """.ifEmpty { d.safeOptString("reasoning_text", "") }""",
            """val hasReasoningTextKey = d.has("reasoning_text")""",
        )) {
            assertTrue("the reasoning fallback chain lost: $needle", src.contains(needle))
        }
        assertTrue(
            "presence of ANY of the three must mark the turn as having carried reasoning",
            src.contains("if (hasRcKey || hasReasoningKey || hasReasoningTextKey)"),
        )
        assertTrue(
            "the Responses path's own spelling of the same name must stay too",
            src.contains("response.reasoning_text.delta"),
        )
    }

    @Test
    fun `the Copilot model parser still reads the fields Copilot actually sends`() {
        val src = ProductionSources.read("auth/CopilotDeviceFlow.kt")
        assertTrue(
            "adaptive_thinking is one of the two real reasoning signals",
            src.contains("adaptive_thinking"),
        )
        assertTrue(
            "…and a reasoning_effort tier list is the other",
            src.contains("reasoning_effort"),
        )
        assertTrue(
            "the legacy key is kept only as a FALLBACK — Copilot never sends it",
            src.contains("thinking"),
        )
    }
}
