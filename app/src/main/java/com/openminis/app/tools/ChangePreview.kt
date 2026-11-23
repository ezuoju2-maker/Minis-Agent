package com.openminis.app.tools

/**
 * [code-ability / diff-preview] Pure, dependency-free line diff and change
 * summary used to tell the user *what* a file_write / file_edit actually
 * changed, and to let the agent self-verify an edit before moving on.
 *
 * Design goals:
 *  - Deterministic and side-effect free: given the same before/after text the
 *    output is byte-for-byte identical, so it is safe to unit test and to hash.
 *  - Bounded: a huge file must not produce a huge preview. [MAX_CONTEXT_LINES]
 *    keeps the LCS window small, and [MAX_PREVIEW_LINES] caps the rendered hunks.
 *  - Honest: when the input exceeds the LCS budget the summary says so instead
 *    of silently producing a misleading diff.
 *
 * This is deliberately NOT a git/diff library — no external dependency, works
 * on the JVM unit-test classpath as well as on device.
 */
object ChangePreview {

    const val MAX_CONTEXT_LINES = 400
    const val MAX_PREVIEW_LINES = 60

    data class Stats(
        val added: Int,
        val removed: Int,
        val unchanged: Int,
        /** True when inputs were truncated to [MAX_CONTEXT_LINES] for the LCS. */
        val approximate: Boolean,
    ) {
        val changed: Int get() = added + removed
    }

    data class Line(val kind: Kind, val text: String, val oldNumber: Int, val newNumber: Int) {
        enum class Kind { ADD, REMOVE, CONTEXT }
        val marker: String get() = when (kind) {
            Kind.ADD -> "+"
            Kind.REMOVE -> "-"
            Kind.CONTEXT -> " "
        }
    }

    /** Split like a line-based diff: keep line content, ignore the final empty segment. */
    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split('\n', '\r').toMutableList()
        if (raw.lastOrNull() == "") raw.removeAt(raw.size - 1)
        return raw
    }

    /**
     * Longest-common-subsequence diff over the two line lists. O(n*m) time and
     * O(n*m) space, bounded by [MAX_CONTEXT_LINES] per side so a large file can
     * never blow the heap (file_edit itself refuses >8MB, but the tool result
     * text can still be large).
     */
    fun diff(before: String, after: String): List<Line> {
        val a = lines(before)
        val b = lines(after)
        val approximate = a.size > MAX_CONTEXT_LINES || b.size > MAX_CONTEXT_LINES
        val aa = a.take(MAX_CONTEXT_LINES)
        val bb = b.take(MAX_CONTEXT_LINES)
        val n = aa.size
        val m = bb.size

        // dp[i][j] = LCS length of aa[i..) and bb[j..).
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (aa[i] == bb[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<Line>()
        var i = 0
        var j = 0
        var oldNo = 1
        var newNo = 1
        while (i < n && j < m) {
            when {
                aa[i] == bb[j] -> {
                    out.add(Line(Line.Kind.CONTEXT, aa[i], oldNo++, newNo++))
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(Line(Line.Kind.REMOVE, aa[i], oldNo++, newNo))
                    i++
                }
                else -> {
                    out.add(Line(Line.Kind.ADD, bb[j], oldNo, newNo++))
                    j++
                }
            }
        }
        while (i < n) out.add(Line(Line.Kind.REMOVE, aa[i++], oldNo++, newNo))
        while (j < m) out.add(Line(Line.Kind.ADD, bb[j++], oldNo, newNo++))

        // Collapse to a bounded window around the first change so the preview
        // stays small even when the edit is near the end of a long file.
        return collapse(out, approximate)
    }

    private fun collapse(all: List<Line>, approximate: Boolean): List<Line> {
        if (all.size <= MAX_PREVIEW_LINES) return all
        val firstChange = all.indexOfFirst { it.kind != Line.Kind.CONTEXT }
        if (firstChange < 0) return all.take(MAX_PREVIEW_LINES)
        val start = (firstChange - 8).coerceAtLeast(0)
        val end = (start + MAX_PREVIEW_LINES).coerceAtMost(all.size)
        val window = all.subList(start, end).toMutableList()
        val prefixOmitted = start
        val suffixOmitted = all.size - end
        val note = buildString {
            append("… diff truncated (${all.size} changed-region lines")
            if (approximate) append(", input exceeded ${MAX_CONTEXT_LINES} lines: approximate")
            append(")")
        }
        // Reuse a CONTEXT line to carry the note without adding a new kind.
        if (prefixOmitted > 0) window.add(0, Line(Line.Kind.CONTEXT, note, 0, 0))
        if (suffixOmitted > 0) window.add(Line(Line.Kind.CONTEXT, "… +$suffixOmitted more lines", 0, 0))
        return window
    }

    /** Compact counts without rendering the diff — cheap enough for every edit. */
    fun stats(before: String, after: String): Stats {
        val d = diff(before, after)
        return Stats(
            added = d.count { it.kind == Line.Kind.ADD },
            removed = d.count { it.kind == Line.Kind.REMOVE },
            unchanged = d.count { it.kind == Line.Kind.CONTEXT },
            approximate = before.linesCount() > MAX_CONTEXT_LINES || after.linesCount() > MAX_CONTEXT_LINES,
        )
    }

    private fun String.linesCount(): Int = if (isEmpty()) 0 else lines(this).size

    /** Render a unified-style preview with line numbers, capped in length. */
    fun render(before: String, after: String, maxChars: Int = 1200): String {
        val d = diff(before, after)
        val note = "… (preview truncated)"
        // Reserve room for the note so adding it can never breach maxChars.
        val rowBudget = (maxChars - note.length).coerceAtLeast(0)
        val sb = StringBuilder()
        var truncated = false
        for (line in d) {
            val row = "${line.marker}${line.oldNumber}/${line.newNumber} ${line.text}"
            if (sb.length + (if (sb.isEmpty()) 0 else 1) + row.length > rowBudget) {
                truncated = true
                break
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(row)
        }
        if (truncated) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(note)
        }
        val out = sb.toString()
        return if (out.length <= maxChars) out else out.take(maxChars)
    }
}
