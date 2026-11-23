package com.openminis.app.ui.chat.search

import androidx.compose.ui.text.AnnotatedString

object ChatSearchMarkdown {
    private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

    fun searchable(text: AnnotatedString): String {
        val spans = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
        if (spans.isEmpty()) return text.text
        val chars = text.text.toCharArray()
        for (s in spans) for (i in s.start until minOf(s.end, chars.size)) chars[i] = '￼'
        return String(chars)
    }
}
