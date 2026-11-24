package com.openminis.app.tools.patch

import com.openminis.app.tools.ChangePreview

data class Hunk(val id: Int, val rows: List<ChangePreview.Line>) {
    val added: Int get() = rows.count { it.kind == ChangePreview.Line.Kind.ADD }
    val removed: Int get() = rows.count { it.kind == ChangePreview.Line.Kind.REMOVE }
    val oldLines: List<String> get() = rows.filter { it.kind != ChangePreview.Line.Kind.ADD }.map { it.text }
    val newLines: List<String> get() = rows.filter { it.kind != ChangePreview.Line.Kind.REMOVE }.map { it.text }
}

class PatchPlan(
    val beforeLines: List<String>,
    val hunks: List<Hunk>,
    val complete: Boolean,
    private val trailingNewline: Boolean,
) {

    fun apply(accepted: Set<Int>): String {
        val out = ArrayList<String>(beforeLines.size + hunks.size * 2)
        var cursor = 0
        for (h in hunks) {
            val firstOld = h.rows.firstOrNull { it.kind != ChangePreview.Line.Kind.ADD }?.oldNumber ?: (cursor + 1)
            val startIdx = (firstOld - 1).coerceAtLeast(cursor).coerceAtMost(beforeLines.size)
            while (cursor < startIdx) out.add(beforeLines[cursor++])
            if (h.id in accepted) out.addAll(h.newLines) else out.addAll(h.oldLines)
            cursor = (cursor + h.oldLines.size).coerceAtMost(beforeLines.size)
        }
        while (cursor < beforeLines.size) out.add(beforeLines[cursor++])
        val joined = out.joinToString("\n")
        return if (trailingNewline && joined.isNotEmpty()) joined + "\n" else joined
    }

    fun ids(): List<Int> = hunks.map { it.id }
}

object PatchSplitter {

    fun plan(before: String, after: String, context: Int = 3): PatchPlan {
        val rows = ChangePreview.diff(before, after)
        val truncated = rows.any { it.oldNumber == 0 && it.newNumber == 0 }
        val hunks = split(rows, context.coerceAtLeast(0))
        return PatchPlan(splitLines(before), hunks, complete = !truncated, trailingNewline = before.endsWith("\n") || after.endsWith("\n"))
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split('\n').toMutableList()
        if (raw.lastOrNull() == "") raw.removeAt(raw.size - 1)
        return raw
    }

    private fun split(rows: List<ChangePreview.Line>, context: Int): List<Hunk> {
        if (rows.isEmpty()) return emptyList()
        val hunks = ArrayList<Hunk>()
        val current = ArrayList<ChangePreview.Line>()
        var contextRun = 0
        var dirty = false
        fun flush(keepTail: Int) {
            if (!dirty) {
                current.clear()
                contextRun = 0
                return
            }
            val drop = (contextRun - keepTail).coerceAtLeast(0)
            repeat(drop) { current.removeAt(current.size - 1) }
            hunks.add(Hunk(hunks.size, current.toList()))
            current.clear()
            contextRun = 0
            dirty = false
        }
        for (row in rows) {
            if (row.oldNumber == 0 && row.newNumber == 0) continue
            if (row.kind == ChangePreview.Line.Kind.CONTEXT) {
                contextRun++
                current.add(row)
                if (dirty && contextRun > 2 * context) flush(context)
            } else {
                dirty = true
                contextRun = 0
                current.add(row)
            }
        }
        flush(context)
        if (current.isNotEmpty()) {
            hunks.add(Hunk(hunks.size, current.toList()))
        }
        return hunks
    }
}
