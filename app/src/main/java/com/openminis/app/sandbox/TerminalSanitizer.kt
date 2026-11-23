package com.openminis.app.sandbox

object TerminalSanitizer {

    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    private const val PRE_SANITIZE_MAX_CHARS = 400_000

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        val pre = truncateIfNeeded(raw, PRE_SANITIZE_MAX_CHARS)

        val crFolded = foldCarriageReturns(pre)

        val stripped = ANSI_REGEX.replace(crFolded, "")

        val cleaned = stripped.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "")

        return noNullLines.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()
        val matcher = ANSI_REGEX.toPattern().matcher("")

        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')

            if ('\r' !in line) {
                result.append(line)
                continue
            }

            matcher.reset(line)
            val buffer = StringBuilder(line.length)
            var cursor = 0
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\r') {
                    cursor = 0
                    i++
                    continue
                }
                if (c == '\u001B') {
                    matcher.region(i, line.length)
                    if (matcher.lookingAt()) {
                        val seq = matcher.group()
                        if (seq.length >= 3 && seq[1] == '[' && seq.last() == 'K') {
                            eraseInLine(buffer, cursor, seq.substring(2, seq.length - 1))
                        }
                        i = matcher.end()
                    } else {
                        i++
                    }
                    continue
                }
                if (c != '\t' && c.code < 0x20) {
                    i++
                    continue
                }
                if (cursor < buffer.length) buffer.setCharAt(cursor, c) else buffer.append(c)
                cursor++
                i++
            }
            result.append(buffer)
        }

        return result.toString()
    }

    private fun eraseInLine(buffer: StringBuilder, cursor: Int, param: String) {
        when (param) {
            "", "0" -> if (cursor < buffer.length) buffer.setLength(cursor)
            "1" -> for (k in 0..minOf(cursor, buffer.length - 1)) buffer.setCharAt(k, ' ')
            "2" -> {
                val keep = minOf(cursor, buffer.length)
                buffer.setLength(keep)
                for (k in 0 until keep) buffer.setCharAt(k, ' ')
            }
        }
    }
}
