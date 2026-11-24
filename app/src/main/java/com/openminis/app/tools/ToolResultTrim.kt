package com.openminis.app.tools

object ToolResultTrim {

    val IMPORTANT_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\b(error|fatal|exception|failed|failure|panic|denied|refused|timeout|timed out)\b"""),
        Regex("""(?i)\b(assertion|traceback|stack trace|cannot|unable to|not found|no such)\b"""),
        Regex("""(?i)^\s*(PASS|FAIL|OK|FAILED)\b"""),
        Regex("""(?i)(错误|失败|异常|超时|拒绝|无权限|不存在|找不到|命令未找到)"""),
    )

    data class Trimmed(
        val text: String,
        val originalLines: Int,
        val keptLines: Int,
        val omittedLines: Int,
        val structuredPayload: Boolean = false,
        val omittedChars: Int = 0,
    ) {
        val wasTrimmed: Boolean get() = omittedLines > 0 || omittedChars > 0

        val omittedPercent: Int
            get() = if (originalLines <= 0) 0
            else ((omittedLines.toLong() * 100L) / originalLines.toLong()).toInt()

        private fun charSuffix(): String = if (omittedChars > 0) ", " + omittedChars + " chars dropped" else ""

        fun noticeText(): String {
            val base = "[context-note: truncated before it reached you, kept " + keptLines + " of " + originalLines +
                " lines (~" + omittedPercent + "% omitted" + charSuffix() + "). If the missing middle matters, re-run with a" +
                " narrower scope (head/tail/grep/sed -n) instead of dumping everything again.]"
            if (!structuredPayload) return base
            return base + " [structured-payload: this was valid JSON before truncation, so do not copy it" +
                " back verbatim or parse it as a whole; re-read the original in smaller pieces.]"
        }
    }

    fun trim(text: String, maxLines: Int = 240, maxChars: Int = 24_000): Trimmed {
        val lineCap = maxLines.coerceAtLeast(1)
        val charCap = maxChars.coerceAtLeast(1)
        val all = if (text.isEmpty()) emptyList() else text.lineSequence().toList()
        val original = all.size
        if (all.size <= lineCap && text.length <= charCap) {
            return Trimmed(text, original, original, 0)
        }

        val headCount = minOf(all.size, maxOf(1, lineCap / 4))
        val tailRoom = minOf(lineCap - headCount - 1, lineCap / 2)
        val tailCount = if (tailRoom <= 0) 0 else minOf(all.size - headCount, tailRoom)
        val importantBudget = maxOf(0, lineCap - headCount - tailCount)

        val head = all.take(headCount)
        val tail = if (tailCount > 0) all.takeLast(tailCount) else emptyList()
        val middle = if (all.size > headCount + tailCount) {
            all.subList(headCount, all.size - tailCount)
        } else {
            emptyList()
        }

        val importantIndices = if (importantBudget <= 0) emptyList()
        else middle.indices.filter { isImportant(middle[it]) }.take(importantBudget)
        val important = importantIndices.map { middle[it] }

        val kept = ArrayList<String>(head.size + important.size + tail.size + 2)
        kept.addAll(head)
        val firstImportant = importantIndices.firstOrNull() ?: middle.size
        if (firstImportant > 0) kept.add(omittedMarker(firstImportant))
        kept.addAll(important)
        val lastImportant = importantIndices.lastOrNull()
        val omittedAfter = if (lastImportant == null) {
            (middle.size - firstImportant).coerceAtLeast(0)
        } else {
            (middle.size - lastImportant - 1).coerceAtLeast(0)
        }
        if (omittedAfter > 0) kept.add(omittedMarker(omittedAfter))
        kept.addAll(tail)

        val collapsed = collapseRepeats(kept)
        var out = collapsed.joinToString("\n")
        var charsDropped = 0
        if (out.length > charCap) {
            val marker = omittedCharsMarker(out.length)
            val keep = charCap - marker.length - 2
            if (keep < 64) {
                charsDropped = out.length - charCap
                out = out.takeLast(charCap)
            } else {
                val headChars = (keep * 2) / 5
                val tailChars = keep - headChars
                val removedChars = out.length - headChars - tailChars
                charsDropped = removedChars
                out = out.take(headChars) + "\n" + omittedCharsMarker(removedChars) + "\n" + out.takeLast(tailChars)
            }
        }
        val keptCount = head.size + important.size + tail.size
        val structured = looksStructured(text)
        return Trimmed(out, original, keptCount, (original - keptCount).coerceAtLeast(0), structured, charsDropped)
    }

    private fun looksStructured(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return false
        val c = text[first]
        if (c != '{' && c != '[') return false
        val last = text.indexOfLast { !it.isWhitespace() }
        if (last <= first) return false
        val close = text[last]
        return (c == '{' && close == '}') || (c == '[' && close == ']')
    }

    fun isImportant(line: String): Boolean {
        if (line.isEmpty()) return false
        for (pattern in IMPORTANT_PATTERNS) if (pattern.containsMatchIn(line)) return true
        return false
    }

    private fun collapseRepeats(lines: List<String>): List<String> {
        if (lines.size < 3) return lines
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val cur = lines[i]
            var j = i + 1
            if (cur.isNotEmpty() && !isMarker(cur)) {
                while (j < lines.size && lines[j] == cur) j++
            }
            val repeat = j - i
            if (repeat >= 3) {
                out.add(cur)
                out.add("\u2026 [repeated " + (repeat - 1) + " more times] \u2026")
            } else {
                for (k in i until j) out.add(lines[k])
            }
            i = j
        }
        return out
    }

    private fun isMarker(line: String): Boolean = line.startsWith("\u2026 [")

    private fun omittedMarker(lines: Int): String = "\u2026 [" + lines + " lines omitted] \u2026"

    private fun omittedCharsMarker(chars: Int): String = "\u2026 [" + chars + " chars omitted] \u2026"
}
