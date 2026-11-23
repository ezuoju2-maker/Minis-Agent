package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OpenCodeSessionHeaderWireTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel.gpt4oMini,
        basePath = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `a non-opencode host receives no session header`() = runBlocking {
        val p = provider().apply { sessionId = "3f2a1c7e-9b04-4d55-8e21-0a7c6f5b3d19" }
        p.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64)
        assertNull(
            "the conversation id must not reach a non-OpenCode endpoint",
            server.takeRequest().getHeader(OpenCodeSessionHeader.HEADER),
        )
    }

    @Test
    fun `a provider with no session id sends no header`() = runBlocking {
        provider().sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64)
        assertNull(server.takeRequest().getHeader(OpenCodeSessionHeader.HEADER))
    }

    @Test
    fun `the session id is readable back off the provider`() {
        val p = provider()
        assertNull(p.sessionId)
        p.sessionId = "abc"
        assertEquals("abc", p.sessionId)
    }
}
