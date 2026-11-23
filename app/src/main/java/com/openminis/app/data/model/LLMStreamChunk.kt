package com.openminis.app.data.model

import org.json.JSONObject

sealed class LLMStreamChunk {
    data object Started : LLMStreamChunk()
    data class Text(val text: String) : LLMStreamChunk()
    data class Usage(val usage: LLMUsage) : LLMStreamChunk()
    data class Finished(
        val stopReason: String?,
        val refusalCategory: String? = null,
        val refusalExplanation: String? = null,
    ) : LLMStreamChunk()

    data class ThinkingDelta(val text: String) : LLMStreamChunk()

    data class ReasoningContent(val content: String) : LLMStreamChunk()

    data class ToolUseStart(val id: String, val name: String) : LLMStreamChunk()
    data class ToolInputDelta(val id: String, val accumulated: String) : LLMStreamChunk()
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        val thoughtSignature: String? = null,
    ) : LLMStreamChunk()

    data class MediaAttachment(val attachment: LLMMediaAttachment) : LLMStreamChunk()
}
