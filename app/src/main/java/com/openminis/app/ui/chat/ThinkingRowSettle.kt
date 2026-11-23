package com.openminis.app.ui.chat

internal object ThinkingRowSettle {

    fun settled(messages: List<ChatMessage>): List<ChatMessage>? {
        if (messages.none { it.isStreaming || it.isAwaitingModelResponse }) return null
        return messages.map {
            if (it.isStreaming || it.isAwaitingModelResponse) it.copy(isStreaming = false, isAwaitingModelResponse = false) else it
        }
    }
}
