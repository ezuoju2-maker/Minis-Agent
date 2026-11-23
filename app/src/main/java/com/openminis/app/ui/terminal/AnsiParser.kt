package com.openminis.app.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

object AnsiParser {

    private val standardColors = arrayOf(
        Color(0xFF000000),
        Color(0xFFCC0000),
        Color(0xFF00CC00),
        Color(0xFFCCCC00),
        Color(0xFF5555FF),
        Color(0xFFCC00CC),
        Color(0xFF00CCCC),
        Color(0xFFCCCCCC),
    )

    private val brightColors = arrayOf(
        Color(0xFF555555),
        Color(0xFFFF5555),
        Color(0xFF55FF55),
        Color(0xFFFFFF55),
        Color(0xFF5555FF),
        Color(0xFFFF55FF),
        Color(0xFF55FFFF),
        Color(0xFFFFFFFF),
    )

    private val CSI_REGEX = Regex("""\x1B\[([0-9;]*)m""")

    private val OTHER_ESC_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-ln-z]|\][^\x07]*(?:\x07|\x1B\\)|[()][0-2AB]|[A-Za-z])"""
    )

    fun parse(text: String, defaultColor: Color = Color(0xFFD4D4D4)): AnnotatedString {
        val cleaned = OTHER_ESC_REGEX.replace(text, "")

        return buildAnnotatedString {
            var fg = defaultColor
            var bold = false
            var italic = false
            var underline = false
            var strikethrough = false

            var pos = 0
            for (match in CSI_REGEX.findAll(cleaned)) {
                if (match.range.first > pos) {
                    val style = buildStyle(fg, bold, italic, underline, strikethrough)
                    pushStyle(style)
                    append(cleaned.substring(pos, match.range.first))
                    pop()
                }
                pos = match.range.last + 1

                val params = match.groupValues[1]
                    .split(';')
                    .mapNotNull { it.toIntOrNull() }
                    .ifEmpty { listOf(0) }

                var i = 0
                while (i < params.size) {
                    when (params[i]) {
                        0 -> { fg = defaultColor; bold = false; italic = false; underline = false; strikethrough = false }
                        1 -> bold = true
                        3 -> italic = true
                        4 -> underline = true
                        9 -> strikethrough = true
                        22 -> bold = false
                        23 -> italic = false
                        24 -> underline = false
                        29 -> strikethrough = false
                        in 30..37 -> fg = if (bold) brightColors[params[i] - 30] else standardColors[params[i] - 30]
                        39 -> fg = defaultColor
                        in 90..97 -> fg = brightColors[params[i] - 90]
                    }
                    i++
                }
            }

            if (pos < cleaned.length) {
                val style = buildStyle(fg, bold, italic, underline, strikethrough)
                pushStyle(style)
                append(cleaned.substring(pos))
                pop()
            }
        }
    }

    private fun buildStyle(
        fg: Color,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strikethrough: Boolean,
    ): SpanStyle {
        var decoration = TextDecoration.None
        if (underline) decoration = TextDecoration.combine(listOf(decoration, TextDecoration.Underline))
        if (strikethrough) decoration = TextDecoration.combine(listOf(decoration, TextDecoration.LineThrough))
        return SpanStyle(
            color = fg,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
            textDecoration = decoration,
        )
    }
}
