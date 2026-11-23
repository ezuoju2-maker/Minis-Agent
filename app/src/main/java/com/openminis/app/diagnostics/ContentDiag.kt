package com.openminis.app.diagnostics

import java.util.concurrent.atomic.AtomicReference

const val CONTENT_DIAG_MIN_CHARS = 5_000

object ContentDiag {

    data class Summary(
        val chars: Int,
        val lines: Int,
        val paragraphs: Int,
        val tableCount: Int,
        val codeBlockCount: Int,
        val mathCount: Int,
    ) {
        val hasTable: Boolean get() = tableCount > 0
        val hasCodeBlock: Boolean get() = codeBlockCount > 0
        val hasMath: Boolean get() = mathCount > 0

        fun asLogFields(): String =
            "chars=$chars lines=$lines paragraphs=$paragraphs " +
                "hasTable=$hasTable tableCount=$tableCount " +
                "hasCodeBlock=$hasCodeBlock codeBlockCount=$codeBlockCount " +
                "hasMath=$hasMath mathCount=$mathCount"
    }

    private val FENCE = Regex("""(?m)^\s{0,3}(```|~~~)""")
    private val TABLE_SEP = Regex("""(?m)^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    private val DISPLAY_MATH_DOLLAR = Regex("""\$\$""")
    private val DISPLAY_MATH_BRACKET = Regex("""\\\[""")
    private val INLINE_MATH_PAREN = Regex("""\\\(""")

    fun summarize(text: String): Summary {
        if (text.isEmpty()) return Summary(0, 0, 0, 0, 0, 0)
        val lines = text.count { it == '\n' } + 1
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
            .count { it.isNotBlank() }
            .coerceAtLeast(1)
        val fenceMarkers = FENCE.findAll(text).count()
        val codeBlockCount = (fenceMarkers + 1) / 2
        val tableCount = TABLE_SEP.findAll(text).count()
        val displayDollar = DISPLAY_MATH_DOLLAR.findAll(text).count() / 2
        val displayBracket = DISPLAY_MATH_BRACKET.findAll(text).count()
        val inlineParen = INLINE_MATH_PAREN.findAll(text).count()
        val mathCount = displayDollar + displayBracket + inlineParen
        return Summary(
            chars = text.length,
            lines = lines,
            paragraphs = paragraphs,
            tableCount = tableCount,
            codeBlockCount = codeBlockCount,
            mathCount = mathCount,
        )
    }


    data class RenderMarker(val sessionId: String, val messageId: String, val summary: Summary)

    private val current = AtomicReference<RenderMarker?>(null)

    fun setCurrentRender(sessionId: String, messageId: String, summary: Summary) {
        current.set(RenderMarker(sessionId, messageId, summary))
    }

    fun clearCurrentRender(messageId: String) {
        val cur = current.get()
        if (cur != null && cur.messageId == messageId) current.set(null)
    }

    fun currentRenderLogFields(): String {
        val cur = current.get() ?: return ""
        return " renderSession=${cur.sessionId} renderMsg=${cur.messageId} ${cur.summary.asLogFields()}"
    }
}
