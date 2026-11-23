package com.openminis.app.ui.chat.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString


internal val LocalChatSearchHighlight = compositionLocalOf { ChatSearchHighlight.NONE }

internal val LocalChatSearchRow = compositionLocalOf<String?> { null }

internal val ChatSearchMatchBackground = Color(0x66FFD60A)
internal val ChatSearchCurrentBackground = Color(0xCCFF9F0A)

internal fun applyChatSearchHighlight(
    text: AnnotatedString,
    key: ChatSearchUnitKey,
    highlight: ChatSearchHighlight,
    match: SpanStyle = SpanStyle(background = ChatSearchMatchBackground),
    current: SpanStyle = SpanStyle(background = ChatSearchCurrentBackground),
    offset: Int = 0,
): AnnotatedString {
    val count = highlight.count(key)
    if (count <= 0 || offset > text.length) return text
    val searchable = ChatSearchMarkdown.searchable(text).substring(offset)
    val ranges = ChatSearchMatcher.ranges(highlight.query, searchable, count)
    if (ranges.isEmpty()) return text
    val currentOrdinal = highlight.currentOrdinal(key)
    return buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { i, r ->
            addStyle(if (i == currentOrdinal) current else match, offset + r.first, offset + r.last + 1)
        }
    }
}

@Composable
internal fun rememberChatSearchHighlighted(text: String, field: String, offset: Int = 0): AnnotatedString {
    val row = LocalChatSearchRow.current
    val h = LocalChatSearchHighlight.current
    return remember(text, row, field, h, offset) {
        val base = AnnotatedString(text)
        if (row == null) base else applyChatSearchHighlight(base, ChatSearchUnitKey(row, field), h, offset = offset)
    }
}

@Composable
internal fun rememberChatSearchHighlighted(text: AnnotatedString, field: String?): AnnotatedString {
    val row = LocalChatSearchRow.current
    val h = LocalChatSearchHighlight.current
    if (row == null || field == null || h.countByUnit.isEmpty()) return text
    return remember(text, row, field, h) { applyChatSearchHighlight(text, ChatSearchUnitKey(row, field), h) }
}

@Composable
internal fun chatSearchFieldIsCurrent(field: String?): Boolean {
    val row = LocalChatSearchRow.current ?: return false
    if (field == null) return false
    return LocalChatSearchHighlight.current.current?.key == ChatSearchUnitKey(row, field)
}

@Composable
internal fun ChatSearchRowScope(controller: ChatSearchController, rowKey: String, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChatSearchHighlight provides controller.highlight,
        LocalChatSearchRow provides rowKey,
        content = content,
    )
}
