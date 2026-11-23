package com.openminis.app.shared

object SentenceSplitter {

    val TERMINATORS: Set<Char> = setOf(
        '。', '！', '？', '；', '…',
        '.', '!', '?', ';',
    )

    fun isTerminator(c: Char): Boolean = TERMINATORS.contains(c)

    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (isTerminator(ch)) {
                val trimmed = current.toString().trim()
                if (trimmed.isNotEmpty()) sentences.add(trimmed)
                current.setLength(0)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) sentences.add(tail)
        return sentences
    }
}
