package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.speech.correction.ScreenContextBuilder
import com.openminis.app.speech.correction.TypedVocabularyBuilder

internal class VoiceScreenItemsHolder {
    @Volatile
    var items: List<FlatChatItem> = emptyList()
}

internal fun FlatChatItem.toScreenSegment(): ScreenContextBuilder.Segment = when (this) {
    is FlatChatItem.UserBubble -> ScreenContextBuilder.Segment(
        key, message.id, ScreenContextBuilder.Kind.USER,
        TypedVocabularyBuilder.stripAttachmentMarkup(message.content),
    )
    is FlatChatItem.AssistantText -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, block.content,
    )
    is FlatChatItem.AssistantMarkdownBlock -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, rawText,
    )
    is FlatChatItem.AssistantLegacyContent -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, content,
    )
    is FlatChatItem.AssistantToolUse -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.TOOL, block.toolTitle.ifBlank { block.toolName },
    )
    else -> ScreenContextBuilder.Segment(key, "", null, "")
}

internal fun captureVoiceScreenSnapshot(
    listState: LazyListState,
    items: List<FlatChatItem>,
    reverseLayout: Boolean,
): ScreenContextBuilder.Snapshot? {
    val info = listState.layoutInfo
    if (items.isEmpty() || info.visibleItemsInfo.isEmpty()) return null
    return ScreenContextBuilder.Snapshot(
        segments = items.map { it.toScreenSegment() },
        visible = info.visibleItemsInfo.mapNotNull { v ->
            (v.key as? String)?.let { ScreenContextBuilder.VisibleItem(it, v.offset, v.size) }
        },
        viewportStart = info.viewportStartOffset,
        viewportEnd = info.viewportEndOffset,
        olderAtLargerOffsets = reverseLayout,
    )
}
