package com.openminis.app.ui.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect

class MessageBoundsRegistry {
    private data class Entry(val bounds: Rect, val markdown: String)
    private val entries = mutableMapOf<Pair<String, String>, Entry>()

    fun put(messageId: String, slotKey: String, bounds: Rect, markdown: String) {
        entries[messageId to slotKey] = Entry(bounds, markdown)
    }

    fun remove(messageId: String, slotKey: String) {
        entries.remove(messageId to slotKey)
    }

    fun markdownAt(rect: Rect): String? {
        val y = (rect.top + rect.bottom) / 2f
        return entries.values.firstOrNull { y in it.bounds.top..it.bounds.bottom }?.markdown
    }

    fun markdownFor(messageId: String): String? {
        return entries.entries.firstOrNull { it.key.first == messageId }?.value?.markdown
    }
}

val LocalMessageBoundsRegistry = compositionLocalOf<MessageBoundsRegistry?> { null }
