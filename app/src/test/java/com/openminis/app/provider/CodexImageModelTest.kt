package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexImageModelTest {

    private fun imageModel(id: String) = LLMModel(
        id = id,
        displayName = id,
        provider = "OpenAI",
        inputModalities = listOf("text", "image"),
        outputModalities = listOf("image"),
    )

    private fun bodyFor(modelId: String): JSONObject {
        val provider = OpenAIProvider(
            oauthTokenProvider = { "test-token" },
            model = imageModel(modelId),
        )
        val m = OpenAIProvider::class.java
            .getDeclaredMethod("buildCodexImageBody", List::class.java)
            .apply { isAccessible = true }
        val messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "a red bicycle"))
        return m.invoke(provider, messages) as JSONObject
    }

    private fun imageTool(body: JSONObject): JSONObject =
        body.getJSONArray("tools").getJSONObject(0)

    @Test
    fun `gpt-image-2 sends a bare image_generation tool`() {
        val tool = imageTool(bodyFor("gpt-image-2"))
        assertEquals("image_generation", tool.getString("type"))
        assertFalse(
            "gpt-image-2 must not pin a model — the backend picks it",
            tool.has("model"),
        )
    }

    @Test
    fun `the 2 point 5 variants name themselves in the tool`() {
        for (id in listOf("gpt-image-2.5-sunburst", "gpt-image-2.5-flare")) {
            val tool = imageTool(bodyFor(id))
            assertEquals("image_generation", tool.getString("type"))
            assertEquals("tool must name $id", id, tool.getString("model"))
        }
    }

    @Test
    fun `every codex image model keeps the same wire envelope`() {
        for (id in listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare")) {
            val body = bodyFor(id)
            assertEquals("wire model for $id", "gpt-5.5", body.getString("model"))
            assertTrue("$id must still stream", body.getBoolean("stream"))
            assertEquals("$id must send exactly one tool", 1, body.getJSONArray("tools").length())
        }
    }

    @Test
    fun `the user prompt reaches the request`() {
        val input = bodyFor("gpt-image-2.5-flare").getJSONArray("input").getJSONObject(0)
        assertTrue(input.getString("content").contains("a red bicycle"))
    }
}
