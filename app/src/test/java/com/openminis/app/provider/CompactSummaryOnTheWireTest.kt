package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactSummaryOnTheWireTest {

    private fun summaryWrappedText(summary: String): String =
        "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

    private fun injectSummary(target: LLMMessage, summary: String): LLMMessage {
        val wrapped = summaryWrappedText(summary)
        val parts = if (target.contentParts.isEmpty()) {
            target.contentParts
        } else {
            val firstTextIdx = target.contentParts.indexOfFirst { it is AgentContentPart.Text }
            if (firstTextIdx < 0) {
                listOf(AgentContentPart.Text(wrapped)) + target.contentParts
            } else {
                target.contentParts.mapIndexed { i, part ->
                    if (i != firstTextIdx || part !is AgentContentPart.Text) part
                    else AgentContentPart.Text(wrapped + "\n\n" + part.text)
                }
            }
        }
        return target.copy(content = wrapped + "\n\n" + target.content, contentParts = parts)
    }

    private fun model() = LLMModel(
        id = "gpt-5.6-terra",
        displayName = "Test Model",
        provider = "OpenAI",
        inputModalities = listOf("text"),
    )

    private fun provider(useResponses: Boolean) = OpenAIProvider(
        apiKey = "test-key",
        model = model(),
        basePath = "https://example.invalid",
        useResponsesAPI = useResponses,
    )

    private fun bodyText(body: JSONObject): String = body.toString()


    private fun persistedUserTurn(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
        dbMessageId = "db-1",
    )

    private fun synthesizedUserTurn(text: String) =
        LLMMessage(role = LLMMessage.Role.USER, content = text)

    @Test
    fun `summary reaches the Responses API body when the turn carries contentParts`() {
        val injected = injectSummary(persistedUserTurn("say FOXTROT"), "EARLIER-TURNS-SUMMARY")
        val body = provider(useResponses = true).buildResponsesAPIBody(
            messages = listOf(injected),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue(
            "Responses body lost the <context-summary> envelope. This is THE bug: " +
                "the turn carries contentParts, so the builder serialized " +
                "textParts.joinToString() and ignored msg.content. body=$body",
            text.contains("<context-summary>"),
        )
        assertTrue(
            "envelope present but the summary payload itself is missing. body=$body",
            text.contains("EARLIER-TURNS-SUMMARY"),
        )
        assertTrue(
            "the user's own text must survive alongside the injected summary",
            text.contains("say FOXTROT"),
        )
    }

    @Test
    fun `summary reaches the Chat Completions body when the turn carries contentParts`() {
        val injected = injectSummary(persistedUserTurn("say FOXTROT"), "EARLIER-TURNS-SUMMARY")
        val body = provider(useResponses = false).buildRequestBody(
            messages = listOf(injected),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue(
            "Chat Completions body lost the <context-summary> envelope. body=$body",
            text.contains("<context-summary>"),
        )
        assertTrue(
            "envelope present but the summary payload itself is missing. body=$body",
            text.contains("EARLIER-TURNS-SUMMARY"),
        )
        assertTrue(
            "the user's own text must survive alongside the injected summary",
            text.contains("say FOXTROT"),
        )
    }


    @Test
    fun `standalone summary turn survives the Responses API path`() {
        val standalone = synthesizedUserTurn(summaryWrappedText("STANDALONE-SUMMARY"))
        val body = provider(useResponses = true).buildResponsesAPIBody(
            messages = listOf(standalone),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue("standalone summary lost on Responses path. body=$body", text.contains("<context-summary>"))
        assertTrue(text.contains("STANDALONE-SUMMARY"))
    }

    @Test
    fun `standalone summary turn survives the Chat Completions path`() {
        val standalone = synthesizedUserTurn(summaryWrappedText("STANDALONE-SUMMARY"))
        val body = provider(useResponses = false).buildRequestBody(
            messages = listOf(standalone),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue("standalone summary lost on Chat Completions path. body=$body", text.contains("<context-summary>"))
        assertTrue(text.contains("STANDALONE-SUMMARY"))
    }


    @Test
    fun `injection updates both representations, not just content`() {
        val injected = injectSummary(persistedUserTurn("say FOXTROT"), "S")
        assertTrue(
            "content must carry the envelope",
            injected.content.contains("<context-summary>"),
        )
        val partText = injected.contentParts
            .filterIsInstance<AgentContentPart.Text>()
            .joinToString("") { it.text }
        assertTrue(
            "contentParts must carry the envelope too — updating only `content` " +
                "is the exact defect this suite exists to prevent",
            partText.contains("<context-summary>"),
        )
        assertTrue("the original user text must be preserved in parts", partText.contains("say FOXTROT"))
    }

    @Test
    fun `injection into a turn with no text part prepends one instead of editing a non-text part`() {
        val toolOnly = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult("call-1", "shell_execute", "ok")),
        )
        val injected = injectSummary(toolOnly, "S")
        val parts = injected.contentParts
        assertTrue(
            "a Text part carrying the summary should have been prepended",
            (parts.firstOrNull() as? AgentContentPart.Text)?.text?.contains("<context-summary>") == true,
        )
        assertEquals("the existing tool part must be preserved", 2, parts.size)
        assertTrue(parts[1] is AgentContentPart.ToolResult)
    }
}
