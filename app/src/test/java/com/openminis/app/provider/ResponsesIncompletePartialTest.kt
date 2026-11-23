package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
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

class ResponsesIncompletePartialTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun responsesChunks(body: String): List<LLMStreamChunk> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        return runBlocking {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }
    }

    private fun incompleteEvent(reason: String) =
        """data: {"type":"response.incomplete","response":{"status":"incomplete",""" +
            """"usage":{"input_tokens":171671,"output_tokens":0,"total_tokens":171671},""" +
            """"incomplete_details":{"reason":"$reason"}},"sequence_number":10}"""


    @Test
    fun `truncated reply keeps its text and finishes as length`() {
        val chunks = responsesChunks(
            """
            data: {"type":"response.output_text.delta","delta":"Here is the first half of the ans"}

            ${incompleteEvent("max_output_tokens")}

            """.trimIndent(),
        )

        val text = chunks.filterIsInstance<LLMStreamChunk.Text>()
            .joinToString("") { it.text }
        assertEquals("Here is the first half of the ans", text)

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals("expected exactly one Finished, got $chunks", 1, finished.size)
        assertEquals("length", finished.first().stopReason)
    }

    @Test
    fun `content_filter keeps the text that arrived and finishes as a refusal`() {
        val chunks = responsesChunks(
            """
            data: {"type":"response.output_text.delta","delta":"partial before filter"}

            ${incompleteEvent("content_filter")}

            """.trimIndent(),
        )
        assertEquals(
            "partial before filter",
            chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text },
        )
        assertEquals("content_filter", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
    }

    @Test
    fun `content_filter with no output finishes as a refusal instead of throwing`() {
        val chunks = responsesChunks("${incompleteEvent("content_filter")}\n\n")
        assertEquals("content_filter", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
        assertTrue(chunks.none { it is LLMStreamChunk.Text })
    }


    @Test
    fun `whitespace-only output still throws — the reported case`() {
        val ex = runCatching {
            responsesChunks(
                """
                data: {"type":"response.output_text.delta","delta":" "}

                ${incompleteEvent("max_output_tokens")}

                """.trimIndent(),
            )
        }.exceptionOrNull()

        assertTrue("expected a throw, got none", ex != null)
        val msg = generateSequence(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue("message should name the reason, got: $msg", msg.contains("max_output_tokens"))
        assertTrue(
            "message should point at the thinking budget, got: $msg",
            msg.contains("output budget") || msg.contains("Thinking"),
        )
    }

    @Test
    fun `no output at all still throws`() {
        val ex = runCatching {
            responsesChunks("${incompleteEvent("max_output_tokens")}\n\n")
        }.exceptionOrNull()
        assertTrue("expected a throw for a bodyless incomplete", ex != null)
    }

    @Test
    fun `reasoning-only output still throws — reasoning is not an answer`() {
        val ex = runCatching {
            responsesChunks(
                """
                data: {"type":"response.reasoning_summary_text.delta","delta":"thinking hard about it"}

                ${incompleteEvent("max_output_tokens")}

                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue("reasoning alone must not suppress the error", ex != null)
    }


    @Test
    fun `normal completion is unaffected`() {
        val chunks = responsesChunks(
            """
            data: {"type":"response.output_text.delta","delta":"full answer"}

            data: {"type":"response.completed","response":{"status":"completed"}}

            """.trimIndent(),
        )
        assertEquals(
            "full answer",
            chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text },
        )
        assertEquals("stop", chunks.filterIsInstance<LLMStreamChunk.Finished>().first().stopReason)
    }
}
