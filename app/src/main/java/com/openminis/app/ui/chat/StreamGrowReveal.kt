package com.openminis.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntSize

internal fun Modifier.streamGrowReveal(): Modifier = this
    .clipToBounds()
    .animateContentSize(
        animationSpec = tween<IntSize>(STREAM_GROW_DURATION_MS, easing = STREAM_GROW_EASE_OUT),
        alignment = Alignment.TopStart,
    )

internal const val STREAM_GROW_DURATION_MS = 200

internal val STREAM_GROW_EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)

internal class StreamGrowRevealTracker {
    data class Row(val key: String, val ownerMessageId: String?)

    private var previousKeys: Set<String>? = null
    private var previousNewestKey: String? = null

    fun onRows(rows: List<Row>, liveMessageId: String?, pinned: Boolean): Boolean {
        val before = previousKeys
        val newestBefore = previousNewestKey
        val keys = rows.mapTo(HashSet(rows.size)) { it.key }
        previousKeys = keys
        previousNewestKey = rows.lastOrNull()?.key
        if (liveMessageId == null || before == null || !pinned) return false

        val added = rows.filter { it.key !in before }
        if (added.isEmpty() || added.size > MAX_NEW_ROWS) return false
        return added.all { it.ownerMessageId == liveMessageId } &&
            newestBefore != null && newestBefore in keys &&
            rows.takeLast(added.size).map { it.key } == added.map { it.key }
    }

    companion object {
        const val MAX_NEW_ROWS = 3
    }
}

internal fun FlatChatItem.growOwnerMessageId(): String? = when (this) {
    is FlatChatItem.UserBubble,
    is FlatChatItem.AgentCallbackCard,
    is FlatChatItem.ScheduledTaskCardItem -> null
    is FlatChatItem.AssistantHeader -> messageId
    is FlatChatItem.AssistantText -> messageId
    is FlatChatItem.AssistantMarkdownBlock -> messageId
    is FlatChatItem.AssistantThinking -> messageId
    is FlatChatItem.AssistantToolUse -> messageId
    is FlatChatItem.AssistantToolGroup -> messageId
    is FlatChatItem.AssistantInfo -> messageId
    is FlatChatItem.AssistantTyping -> messageId
    is FlatChatItem.AssistantError -> messageId
    is FlatChatItem.AssistantUsage -> messageId
    is FlatChatItem.AssistantLegacyContent -> messageId
}?.substringBefore('#')
