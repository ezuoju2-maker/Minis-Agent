package com.openminis.app.tools

object ToolResultTrim {

    val IMPORTANT_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\b(error|fatal|exception|failed|failure|panic|denied|refused|timeout|timed out)\b"""),
        Regex("""(?i)\b(assertion|traceback|stack trace|cannot|unable to|not found|no such)\b"""),
        Regex("""(?i)^\s*(PASS|FAIL|OK|FAILED)\b"""),
    )

    data class Trimmed(
        val text: String,
        val originalLines: Int,
        val keptLines: Int,
        val omittedLines: Int,
    ) {
        val wasTrimmed: Boolean get() = omittedLines > 0
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
        val tailCount = minOf(all.size - headCount, maxOf(1, lineCap / 2))
        val importantBudget = maxOf(0, lineCap - headCount - tailCount)

        val head = all.take(headCount)
        val tail = if (tailCount > 0) all.takeLast(tailCount) else emptyList()
        val middle = if (all.size > headCount + tailCount)
            all.subList(headCount, all.size - tailCount) else emptyList()

        val importantIndices = middle.indices.filter { isImportant(middle[it]) }.take(importantBudget)
        val important = importantIndices.map { middle[it] }

        val kept = ArrayList<String>(head.size + important.size + tail.size + 2)
        kept.addAll(head)
        val firstImportant = importantIndices.firstOrNull() ?: middle.size
        if (firstImportant > 0) kept.add("… [$firstImportant lines omitted] …")
        kept.addAll(important)
        val lastImportant = importantIndices.lastOrNull()
        val omittedAfter = if (lastImportant == null) middle.size - firstImportant.coerceAtMost(middle.size)
            else middle.size - lastImportant - 1
        if (omittedAfter > 0) kept.add("… [$omittedAfter lines omitted] …")
        kept.addAll(tail)

        var out = kept.joinToString("\n")
        if (out.length > charCap) {
            val prefix = "… [${out.length - charCap} chars omitted] …\n"
            out = if (prefix.length >= charCap) {
                out.takeLast(charCap)
            } else {
                val budget = charCap - prefix.length
                prefix + out.takeLast(budget)
            }
        }
        val keptCount = head.size + important.size + tail.size
        return Trimmed(out, original, keptCount, (original - keptCount).coerceAtLeast(0))
    }

    fun isImportant(line: String): Boolean = IMPORTANT_PATTERNS.any { it.containsMatchIn(line) }
}
