package com.openminis.app.ui.chat

internal object ToolTextTail {
    const val MAX_LINE_CHARS = 400

    const val MAX_SCAN_CHARS = 8_000

    const val ELLIPSIS = "\u2026"

    const val DETAIL_MAX_LINE_CHARS = 4_000

    fun lastLines(
        text: String,
        n: Int,
        maxLineChars: Int = MAX_LINE_CHARS,
        maxScanChars: Int = MAX_SCAN_CHARS,
    ): String {
        if (text.isEmpty() || n <= 0) return ""
        var end = text.length
        if (text[end - 1] == '\n') end--
        val floor = surrogateSafeStart(text, (end - maxScanChars).coerceAtLeast(0), end)
        val lines = ArrayList<String>(n)
        var lineEnd = end
        while (lines.size < n) {
            var nl = lineEnd - 1
            while (nl >= floor && text[nl] != '\n') nl--
            val hitFloor = nl < floor
            val start = if (hitFloor) floor else nl + 1
            var stop = lineEnd
            if (stop > start && text[stop - 1] == '\r') stop--
            lines += tailOf(text, start, stop, maxLineChars, cutBefore = hitFloor && floor > 0)
            if (hitFloor) break
            lineEnd = nl
        }
        lines.reverse()
        return lines.joinToString("\n")
    }

    fun head(text: String, maxChars: Int = MAX_LINE_CHARS): String {
        if (text.length <= maxChars) return text
        var e = maxChars
        if (e > 0 && Character.isHighSurrogate(text[e - 1])) e--
        return text.substring(0, e) + ELLIPSIS
    }

    fun capLongLines(text: String, maxLineChars: Int = DETAIL_MAX_LINE_CHARS): String {
        var sb: StringBuilder? = null
        var start = 0
        var copied = 0
        while (start <= text.length) {
            var stop = text.indexOf('\n', start)
            if (stop < 0) stop = text.length
            if (stop - start > maxLineChars) {
                val b = sb ?: StringBuilder(text.length.coerceAtMost(1 shl 16)).also { sb = it }
                val half = maxLineChars / 2
                var headEnd = start + half
                if (Character.isHighSurrogate(text[headEnd - 1])) headEnd--
                val tailStart = surrogateSafeStart(text, stop - half, stop)
                b.append(text, copied, headEnd).append(' ').append(ELLIPSIS).append(' ')
                copied = tailStart
            }
            start = stop + 1
        }
        val b = sb ?: return text
        return b.append(text, copied, text.length).toString()
    }

    private fun tailOf(text: String, start: Int, stop: Int, maxChars: Int, cutBefore: Boolean): String {
        if (stop - start <= maxChars) {
            val line = text.substring(start, stop)
            return if (cutBefore) ELLIPSIS + line else line
        }
        return ELLIPSIS + text.substring(surrogateSafeStart(text, stop - maxChars, stop), stop)
    }

    private fun surrogateSafeStart(text: String, i: Int, limit: Int): Int =
        if (i in 1 until limit && Character.isLowSurrogate(text[i])) i + 1 else i
}
