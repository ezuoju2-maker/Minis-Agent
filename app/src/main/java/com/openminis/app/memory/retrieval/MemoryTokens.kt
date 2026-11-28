package com.openminis.app.memory.retrieval


object MemoryTokens {

    fun of(text: String): List<String> {
        val out = ArrayList<String>()
        val s = text.lowercase()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isLetterOrDigit()) {
                val start = i
                while (i < s.length && s[i].isLetterOrDigit()) i++
                val run = s.substring(start, i)
                if (run.length >= 2) {
                    out.add(run)
                    if (run.length >= 4) {
                        for (k in 0..run.length - 2) out.add(run.substring(k, k + 2))
                    }
                }
            } else if (isCjk(c)) {
                out.add(c.toString())
                if (i + 1 < s.length && isCjk(s[i + 1])) out.add(s.substring(i, i + 2))
                i++
            } else {
                i++
            }
        }
        return out
    }

    fun isCjk(c: Char): Boolean = (c.code in 0x4E00..0x9FFF) || (c.code in 0x3400..0x4DBF)
}
