package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderServerErrorStatusTest {
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

    @Test
    fun `gemini 503 arrives as a server error carrying its status`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":503,"message":"no_available_workers"}}"""),
        )
        val provider = GeminiProvider(
            apiKey = "test-key",
            model = LLMModel.gemini25Flash,
            basePath = server.url("/").toString().trimEnd('/'),
        )

        val error = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        }.exceptionOrNull()

        assertTrue("expected an LLMError, got $error", error is LLMError)
        val llmError = error as LLMError
        assertEquals(503, llmError.httpServerErrorStatus)
        assertTrue(
            "a 503 must be classified as a server error so group fallback engages",
            llmError.isHttpServerError,
        )
    }

    @Test
    fun `openai 522 with an html body is still a server error`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(522)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body><h1>522 Connection timed out</h1></body></html>"),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
        )

        val error = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        }.exceptionOrNull()

        assertTrue("expected an LLMError, got $error", error is LLMError)
        val llmError = error as LLMError
        assertEquals(
            "a non-JSON 5xx body must not lose its status: ${llmError.message}",
            522,
            llmError.httpServerErrorStatus,
        )
        assertTrue(llmError.isHttpServerError)
    }

    @Test
    fun `openai 400 carries its code but is not a server error`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"max_tokens must be under 5000 tokens"}}"""),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
        )
        val error = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        }.exceptionOrNull() as LLMError
        assertEquals(null, error.httpServerErrorStatus)
        assertTrue("400 must never engage 5xx fallback", !error.isHttpServerError)
    }

    @Test
    fun `gemini 429 stays a rate limit and is not a server error`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":429,"message":"quota"}}"""),
        )
        val provider = GeminiProvider(
            apiKey = "test-key",
            model = LLMModel.gemini25Flash,
            basePath = server.url("/").toString().trimEnd('/'),
        )

        val error = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        }.exceptionOrNull()

        assertTrue("expected RateLimited, got $error", error is LLMError.RateLimited)
        assertEquals(null, (error as LLMError).httpServerErrorStatus)
    }
}
