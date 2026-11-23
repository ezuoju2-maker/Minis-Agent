package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.effectiveInputModalities

internal object ImageInputPreflight {

    fun isLikelyImageRejection(error: Throwable, sentImageCount: Int): Boolean {
        if (sentImageCount <= 0) return false
        val providerError = error as? LLMError.ProviderError ?: return false
        if (providerError.httpStatus != 400) return false
        val detail = providerError.detail.lowercase()
        return NOT_IMAGE_RELATED.none { it in detail }
    }

    private val NOT_IMAGE_RELATED = listOf(
        "tool output", "tool call", "tool_call", "function_call", "tool_result", "tool_use",
        "context length", "context_length", "maximum context", "prompt is too long", "too many tokens",
    )
}
