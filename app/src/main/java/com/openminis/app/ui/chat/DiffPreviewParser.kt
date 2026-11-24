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
}
