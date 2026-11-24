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
    ) {
        val wasTrimmed: Boolean get() = omittedLines > 0

        val omittedPercent: Int
            get() = if (originalLines <= 0) 0
            else ((omittedLines.toLong() * 100L) / originalLines.toLong()).toInt()

        fun noticeText(): String =
            "[context-note: truncated before it reached you, kept " + keptLines + " of " + originalLines +
                " lines (~" + omittedPercent + "% omitted). If the missing middle matters, re-run with a" +
                " narrower scope (head/tail/grep/sed -n) instead of dumping everything again.]"
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

        var out = kept.joinToString("\n")
        if (out.length > charCap) {
            val marker = omittedCharsMarker(out.length)
            val keep = charCap - marker.length - 2
            if (keep < 64) {
                out = out.takeLast(charCap)
            } else {
                val headChars = (keep * 2) / 5
                val tailChars = keep - headChars
                val removedChars = out.length - headChars - tailChars
                out = out.take(headChars) + "\n" + omittedCharsMarker(removedChars) + "\n" + out.takeLast(tailChars)
            }
        }
        val keptCount = head.size + important.size + tail.size
        return Trimmed(out, original, keptCount, (original - keptCount).coerceAtLeast(0))
    }

    fun isImportant(line: String): Boolean {
        if (line.isEmpty()) return false
        for (pattern in IMPORTANT_PATTERNS) if (pattern.containsMatchIn(line)) return true
        return false
    }

    private fun omittedMarker(lines: Int): String = "\u2026 [" + lines + " lines omitted] \u2026"

    private fun omittedCharsMarker(chars: Int): String = "\u2026 [" + chars + " chars omitted] \u2026"
}
