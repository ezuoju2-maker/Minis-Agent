package com.openminis.app.ui.chat

internal object MathLineBreaker {

    private val BREAK_RELATIONS = listOf(
        "\\Leftrightarrow", "\\Rightarrow", "\\approx", "\\equiv",
        "\\neq", "\\leq", "\\geq", "\\ne", "\\le", "\\ge",
        "=", "<", ">",
    )

    fun splitAtTopLevelRelations(latex: String): List<String> {
        if (latex.contains("\\begin")) return listOf(latex)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var leftRight = 0
        var i = 0

        fun matches(token: String, at: Int): Boolean {
            if (!latex.startsWith(token, at)) return false
            val next = at + token.length
            return !(token[0] == '\\' && next < latex.length && latex[next].isLetter())
        }

        while (i < latex.length) {
            val c = latex[i]
            if (c == '\\' && i + 1 < latex.length && (latex[i + 1] == '{' || latex[i + 1] == '}')) {
                current.append(c).append(latex[i + 1])
                i += 2
                continue
            }
            if (c == '{') depth++ else if (c == '}') depth = maxOf(0, depth - 1)
            if (matches("\\left", i)) leftRight++ else if (matches("\\right", i)) leftRight = maxOf(0, leftRight - 1)
            if (depth == 0 && leftRight == 0 && current.isNotBlank()) {
                val token = BREAK_RELATIONS.firstOrNull { matches(it, i) }
                if (token != null) {
                    pieces.add(current.toString())
                    current.setLength(0)
                    current.append(token)
                    i += token.length
                    continue
                }
            }
            current.append(c)
            i++
        }
        if (current.isNotEmpty()) pieces.add(current.toString())
        return pieces
    }

    suspend fun packRows(pieces: List<String>, fits: suspend (String) -> Boolean): List<String> {
        val rows = mutableListOf<String>()
        var current = ""
        for (piece in pieces) {
            val candidate = current + piece
            if (current.isNotEmpty() && !fits(candidate)) {
                rows.add(current)
                current = piece
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }
}
