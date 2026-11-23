package com.openminis.app.speech

object SpeechSentenceSplitter {

    val SENTENCE_ENDERS: Set<Char> =
        charArrayOf('。', '！', '？', '.', '!', '?', '\n').toSet()

    private val SPEECH_PAUSES: Set<Char> =
        charArrayOf('，', ',', '、', '：', ':').toSet()

    private const val SOFT_LIMIT = 60

    fun isIntraNumberSeparator(text: CharSequence, idx: Int): Boolean {
        val ch = text[idx]
        if (ch != '.' && ch != ',') return false
        if (idx <= 0 || idx + 1 >= text.length) return false
        return text[idx - 1].isDigit() && text[idx + 1].isDigit()
    }

    private fun isFence(buffer: CharSequence, idx: Int): Boolean =
        idx + 2 < buffer.length && buffer[idx] == '`' &&
            buffer[idx + 1] == '`' && buffer[idx + 2] == '`'

    fun extractCompleteSentences(
        buffer: StringBuilder,
        streaming: Boolean = true,
    ): List<String> {
        val sentences = mutableListOf<String>()
        var lastBoundary = 0
        var lastPause = -1
        var i = 0
        while (i < buffer.length) {
            if (isFence(buffer, i)) {
                var j = i + 3
                var foundClose = false
                while (j < buffer.length) {
                    if (isFence(buffer, j)) { foundClose = true; break }
                    j++
                }
                if (!foundClose) {
                    if (!streaming) {
                        val s = buffer.substring(lastBoundary, i).trim()
                        if (s.isNotEmpty()) sentences.add(s)
                        lastBoundary = buffer.length
                    }
                    break
                }
                val s = buffer.substring(lastBoundary, i).trim()
                if (s.isNotEmpty()) sentences.add(s)
                i = j + 3
                lastBoundary = i
                lastPause = -1
                continue
            }
            val ch = buffer[i]
            if (isIntraNumberSeparator(buffer, i)) {
                i++
                continue
            }
            if (streaming &&
                i == buffer.length - 1 &&
                (ch == '.' || ch == ',') &&
                i > 0 && buffer[i - 1].isDigit()
            ) {
                break
            }
            if (ch in SPEECH_PAUSES) lastPause = i
            val runLen = i - lastBoundary + 1
            if (ch in SENTENCE_ENDERS) {
                val s = buffer.substring(lastBoundary, i + 1).trim()
                if (s.isNotEmpty()) sentences.add(s)
                lastBoundary = i + 1
                lastPause = -1
            } else if (runLen >= SOFT_LIMIT && lastPause >= lastBoundary) {
                val s = buffer.substring(lastBoundary, lastPause + 1).trim()
                if (s.isNotEmpty()) sentences.add(s)
                lastBoundary = lastPause + 1
                lastPause = -1
            }
            i++
        }
        if (lastBoundary > 0) buffer.delete(0, lastBoundary)
        return sentences
    }
}
