package com.openminis.app.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

fun linkifyUrls(
    text: String,
    onClick: (String) -> Unit,
    linkColor: Color = Color(0xFF0A84FF),
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    val matcher = android.util.Patterns.WEB_URL.matcher(text)

    return buildAnnotatedString {
        var cursor = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val candidate = text.substring(start, end)
            val lower = candidate.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                continue
            }
            if (start > cursor) append(text, cursor, start)
            withLink(
                LinkAnnotation.Url(
                    url = candidate,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                    linkInteractionListener = { onClick(candidate) },
                ),
            ) {
                append(candidate)
            }
            cursor = end
        }
        if (cursor < text.length) append(text, cursor, text.length)
    }
}
