package com.openminis.app.ui.chat

data class DiffRow(val marker: Char, val oldNumber: Int?, val newNumber: Int?, val text: String)

object DiffPreviewParser {

    private const val HEADER = "Changes:\n"

    fun section(toolOutput: String): String? {
        val at = toolOutput.indexOf(HEADER)
        if (at < 0) return null
        val body = toolOutput.substring(at + HEADER.length)
        if (body.isBlank()) return null
        return body
    }

    fun rows(section: String?): List<DiffRow> {
        if (section.isNullOrBlank()) return emptyList()
        val out = ArrayList<DiffRow>()
        for (raw in section.lines()) {
            val line = raw.trimEnd()
            val marker = line.firstOrNull() ?: continue
            if (marker != '+' && marker != '-' && marker != ' ') continue
            val rest = line.substring(1)
            val numbers = rest.substringBefore(' ')
            val text = rest.substringAfter(' ', "").trimStart()
            val parts = numbers.split('/')
            val oldNo = parts.getOrNull(0)?.toIntOrNull()
            val newNo = parts.getOrNull(1)?.toIntOrNull()
            out.add(DiffRow(marker, oldNo, newNo, text))
        }
        return out
    }

    fun stats(rows: List<DiffRow>): Pair<Int, Int> =
        rows.count { it.marker == '+' } to rows.count { it.marker == '-' }

    fun groups(rows: List<DiffRow>): List<Int> {
        val out = ArrayList<Int>(rows.size)
        var group = -1
        var prevOld: Int? = null
        var lastAddAfter: Int? = null
        for (row in rows) {
            if (row.marker == '+') {
                if (group < 0) group = 0
                out.add(group)
                lastAddAfter = row.newNumber
                continue
            }
            val old = row.oldNumber
            if (old != null) {
                val boundary = prevOld != null && (old - prevOld) > 1
                if (group < 0 || boundary) group++
                prevOld = old
            }
            out.add(if (group < 0) 0 else group)
            lastAddAfter = null
        }
        return out
    }
}
