package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.gemini.GeminiProvider
import kotlinx.coroutines.flow.toList
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

class GeminiToolFieldNamingTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun provider() = GeminiProvider(
        apiKey = "test-key",
        model = LLMModel.gemini25Flash,
        basePath = server.url("/").toString().trimEnd('/'),
    )

    private fun tools() = listOf(
        AgentToolDefinition(
            name = "file_write",
            description = "Write a file",
            parameters = mapOf("path" to AgentToolParam("string", "Where to write")),
            required = listOf("path"),
        ),
    )

    private fun assertCamelCaseToolWrapper(body: JSONObject) {
        val toolsArr = body.optJSONArray("tools")
        assertTrue("request must carry a tools array: $body", toolsArr != null && toolsArr.length() == 1)
        val wrapper = toolsArr!!.getJSONObject(0)
        assertTrue(
            "tool wrapper must be `functionDeclarations` (REST spelling): $wrapper",
            wrapper.has("functionDeclarations"),
        )
        assertFalse(
            "`function_declarations` is the proto name and is rejected by relays: $wrapper",
            wrapper.has("function_declarations"),
        )
        assertEquals(
            "the declaration itself must survive the rename",
            "file_write",
            wrapper.getJSONArray("functionDeclarations").getJSONObject(0).getString("name"),
        )
        assertEquals(
            "toolConfig must request AUTO function calling, matching iOS: $body",
            "AUTO",
            body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"),
        )
    }

    @Test
    fun `streaming request declares tools in camelCase with an AUTO toolConfig`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]}," +
                        "\"finishReason\":\"STOP\"}]}\n\n",
                ),
        )
        provider().streamMessage(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "write a file")),
            systemPrompt = null,
            maxTokens = 1024,
            tools = tools(),
        ).toList()

        assertCamelCaseToolWrapper(JSONObject(server.takeRequest().body.readUtf8()))
    }

    @Test
    fun `non-streaming request declares tools in camelCase with an AUTO toolConfig`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},""" +
                        """"finishReason":"STOP"}]}""",
                ),
        )
        provider().sendMessage(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "write a file")),
            systemPrompt = null,
            maxTokens = 1024,
            tools = tools(),
        )

        assertCamelCaseToolWrapper(JSONObject(server.takeRequest().body.readUtf8()))
    }

    @Test
    fun `a request without tools carries neither tools nor toolConfig`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},""" +
                        """"finishReason":"STOP"}]}""",
                ),
        )
        provider().sendMessage(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "hello")),
            systemPrompt = null,
            maxTokens = 1024,
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse("no tools key on a toolless turn: $body", body.has("tools"))
        assertFalse("no toolConfig key on a toolless turn: $body", body.has("toolConfig"))
    }
}
