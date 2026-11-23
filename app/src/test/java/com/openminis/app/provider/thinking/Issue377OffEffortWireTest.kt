package com.openminis.app.provider.thinking

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Issue377OffEffortWireTest {

    private val gpt6Astra = LLMModel(
        id = "gpt-6-astra",
        displayName = "GPT-6 Astra",
        provider = "OpenAI",
        supportsReasoning = true,
        reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
    )

    private val undeclared = LLMModel(
        id = "gpt-5.3",
        displayName = "GPT-5.3",
        provider = "OpenAI",
        supportsReasoning = true,
    )

    private fun provider(m: LLMModel, responses: Boolean) = OpenAIProvider(
        apiKey = "k",
        model = m,
        basePath = "https://api.openai.com/v1",
        useResponsesAPI = responses,
    )

    private fun responsesBody(m: LLMModel): JSONObject =
        provider(m, responses = true).buildResponsesAPIBody(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "summarize this")),
            systemPrompt = null,
            maxTokens = 4096,
            stream = false,
            thinkingLevel = ThinkingLevel.OFF,
        )

    private fun chatBody(m: LLMModel): JSONObject =
        provider(m, responses = false).buildRequestBody(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "summarize this")),
            systemPrompt = null,
            maxTokens = 4096,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )

    private fun nestedEffort(b: JSONObject): String? =
        b.optJSONObject("reasoning")?.optString("effort")?.takeIf { it.isNotEmpty() }

    private fun rootEffort(b: JSONObject): String? =
        b.optString("reasoning_effort").takeIf { it.isNotEmpty() }


    @Test
    fun `responses OFF on a model without none sends its lowest tier`() {
        assertEquals("low", nestedEffort(responsesBody(gpt6Astra)))
    }

    @Test
    fun `responses OFF never sends the value the model rejects`() {
        assertFalse(
            "gpt-6-astra rejects effort=none with HTTP 400",
            nestedEffort(responsesBody(gpt6Astra)) == "none",
        )
    }

    @Test
    fun `responses OFF is unchanged for a model that declares nothing`() {
        assertEquals("none", nestedEffort(responsesBody(undeclared)))
    }


    @Test
    fun `chat OFF stays unconditional for the OpenAI-native family`() {
        assertEquals("none", rootEffort(chatBody(gpt6Astra)))
        assertEquals("none", rootEffort(chatBody(undeclared)))
    }
}
