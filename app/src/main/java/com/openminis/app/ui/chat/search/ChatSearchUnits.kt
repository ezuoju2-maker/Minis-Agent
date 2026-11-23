package com.openminis.app.ui.chat.search

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatSearchPiece
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.chatSearchMarkdownPieces
import com.openminis.app.ui.chat.isModelSwitchNotice

internal object ChatSearchUnits {
    const val BODY = "body"
    const val ERROR = "error"
    const val TITLE = "title"
    fun attachment(index: Int) = "att:$index"
    fun markdown(path: String) = "md:$path"

    const val MARKDOWN_PREFIX = "md:"

    interface TextSource {
        fun markdownPieces(raw: String): List<ChatSearchPiece> = chatSearchMarkdownPieces(raw)

        fun toolCapsule(block: AssistantBlock): String?
    }

    class PieceCacheEntry(val raw: String, val pieces: List<ChatSearchPiece>)

    fun build(
        rows: List<FlatChatItem>,
        source: TextSource,
        cache: Map<String, PieceCacheEntry> = emptyMap(),
        isCancelled: () -> Boolean = { false },
    ): Pair<List<ChatSearchUnit>, Map<String, PieceCacheEntry>>? {
        val units = ArrayList<ChatSearchUnit>(rows.size * 2)
        val newCache = HashMap<String, PieceCacheEntry>()
        fun add(row: String, field: String, text: String) {
            if (text.isNotEmpty()) units.add(ChatSearchUnit(ChatSearchUnitKey(row, field), text))
        }
        fun markdown(row: String, raw: String) {
            if (raw.isEmpty()) return
            val hit = cache[row]?.takeIf { it.raw == raw }
            val pieces = hit?.pieces ?: source.markdownPieces(raw)
            newCache[row] = hit ?: PieceCacheEntry(raw, pieces)
            for (p in pieces) add(row, markdown(p.path), p.text)
        }
        for ((i, row) in rows.withIndex()) {
            if (i % 32 == 0 && isCancelled()) return null
            when (row) {
                is FlatChatItem.UserBubble -> {
                    val m = row.message
                    m.attachmentNames.drop(m.imageUris.size).forEachIndexed { k, name -> add(row.key, attachment(k), name) }
                    if (m.content.isNotBlank()) add(row.key, BODY, m.content)
                }
                is FlatChatItem.AgentCallbackCard -> add(row.key, TITLE, row.callback.title)
                is FlatChatItem.AssistantMarkdownBlock -> markdown(row.key, row.rawText)
                is FlatChatItem.AssistantText -> markdown(row.key, row.block.content)
                is FlatChatItem.AssistantLegacyContent -> markdown(row.key, row.content)
                is FlatChatItem.AssistantToolUse -> source.toolCapsule(row.block)?.let { add(row.key, BODY, it) }
                is FlatChatItem.AssistantInfo ->
                    if (!isModelSwitchNotice(row.block)) add(row.key, BODY, row.block.content)
                is FlatChatItem.AssistantError -> add(row.key, ERROR, row.error)
                is FlatChatItem.AssistantHeader, is FlatChatItem.AssistantThinking,
                is FlatChatItem.AssistantToolGroup, is FlatChatItem.AssistantTyping,
                is FlatChatItem.AssistantUsage, is FlatChatItem.ScheduledTaskCardItem -> Unit
            }
        }
        return units to newCache
    }
}
