package com.openminis.app.ui.sessions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.openminis.app.data.repository.SessionSearch

@Composable
fun highlightedAnnotatedString(text: String, query: String): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return AnnotatedString(text)
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer
    val ranges = SessionSearch.hitRanges(text, SessionSearch.searchTerms(query))
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var idx = 0
        for (r in ranges) {
            if (r.first > idx) append(text.substring(idx, r.first))
            withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
                append(text.substring(r.first, r.last + 1))
            }
            idx = r.last + 1
        }
        if (idx < text.length) append(text.substring(idx))
    }
}

fun snippetAround(text: String, query: String, radius: Int = 50): String? {
    if (query.isBlank() || text.isEmpty()) return null
    val lower = text.lowercase()
    val pos = lower.indexOf(query.lowercase())
    if (pos < 0) return null
    val start = (pos - radius).coerceAtLeast(0)
    val end = (pos + query.length + radius).coerceAtMost(text.length)
    val core = text.substring(start, end).replace('\n', ' ').replace('\r', ' ')
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + core + suffix
}
