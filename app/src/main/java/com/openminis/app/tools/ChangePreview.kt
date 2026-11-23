package com.openminis.app.tools

object ChangePreview {

    const val MAX_CONTEXT_LINES = 400
    const val MAX_PREVIEW_LINES = 60

    data class Stats(
        val added: Int,
        val removed: Int,
        val unchanged: Int,
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

    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split('\n', '\r').toMutableList()
        if (raw.lastOrNull() == "") raw.removeAt(raw.size - 1)
        return raw
    }

    fun diff(before: String, after: String): List<Line> {
        val a = lines(before)
        val b = lines(after)
        val approximate = a.size > MAX_CONTEXT_LINES || b.size > MAX_CONTEXT_LINES
        val aa = a.take(MAX_CONTEXT_LINES)
        val bb = b.take(MAX_CONTEXT_LINES)
        val n = aa.size
        val m = bb.size

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
        if (prefixOmitted > 0) window.add(0, Line(Line.Kind.CONTEXT, note, 0, 0))
        if (suffixOmitted > 0) window.add(Line(Line.Kind.CONTEXT, "… +$suffixOmitted more lines", 0, 0))
        return window
    }

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

    fun render(before: String, after: String, maxChars: Int = 1200): String {
        val d = diff(before, after)
        val note = "… (preview truncated)"
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
