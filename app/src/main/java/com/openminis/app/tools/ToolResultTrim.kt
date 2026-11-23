package com.openminis.app.tools

/**
 * [token-efficiency / no-capability-loss] Smart, structure-aware trimming of
 * large tool results before they re-enter the model context.
 *
 * Naive `take(n)` drops the tail — but the most informative lines of a build,
 * test run or stack trace are usually at the END (the error / summary). This
 * trims the middle while keeping:
 *   - the head (context: what was being run),
 *   - the tail (the verdict: errors, PASS/FAIL, summary),
 *   - any line matching an "important" pattern (ERROR/FAIL/Exception/...) that
 *     appears anywhere, deduplicated, so a failure in the middle is not lost.
 *
 * The result explicitly says how many lines were omitted, so the agent knows
 * it can re-fetch with a narrower query — no silent capability loss.
 *
 * v2.0 audit fixes:
 *  - [FIX-05] maxChars 裁剪越界：v1.61 用 `out.takeLast(maxChars - 30)`，当
 *    maxChars < 30 时抛 IllegalArgumentException，且前缀长度不固定导致结果仍
 *    可能超过 maxChars。现在按前缀真实长度计算预算并钳到 [0, maxChars]；
 *  - [FIX-06] importantSet 用「行内容」去重，重复出现的错误行会让省略计数
 *    错位。改用中间段的「下标」集合；
 *  - [FIX-07] maxLines / maxChars 的非正值统一钳到最小 1，避免调用方传 0。
 */
object ToolResultTrim {

    /** Lines carrying these tokens are kept wherever they occur. Case-insensitive. */
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

    /**
     * Trim [text] to at most [maxLines] lines / [maxChars] chars, whichever
     * binds first. Returns the original text (unchanged instance) when it fits.
     */
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

        // [FIX-06] Work with indices, not with a set of line strings, so two
        // identical "ERROR" lines are not treated as one for omission counts.
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
            // Tail is the verdict; preserve it by trimming from the front.
            // [FIX-05] Budget accounts for the REAL prefix length, so the
            // returned text never exceeds charCap and never asks takeLast()
            // for a negative count.
            val prefix = "… [${out.length - charCap} chars omitted] …\n"
            out = if (prefix.length >= charCap) {
                // A cap smaller than the marker itself: keep the tail verbatim.
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
