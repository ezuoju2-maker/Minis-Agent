package com.openminis.app.provider.wire

object AssistantWire {

    fun contentFor(text: String, hasToolCalls: Boolean): String? = when {
        text.isNotBlank() -> text
        hasToolCalls -> null
        else -> ""
    }

    fun reasoningFor(reasoning: String?, thinkingEnabled: Boolean, placeholderAllowed: Boolean): String? =
        when {
            !thinkingEnabled -> null
            reasoning != null -> reasoning
            placeholderAllowed -> ""
            else -> null
        }

    fun isLegalToolCallTurn(text: String, hasToolCalls: Boolean): Boolean =
        !(hasToolCalls && text.isBlank())

    fun textBlock(text: String): String? = text.takeIf { it.isNotBlank() }

    fun thinkingBlock(reasoning: String?): String? = reasoning?.takeIf { it.isNotBlank() }
}
