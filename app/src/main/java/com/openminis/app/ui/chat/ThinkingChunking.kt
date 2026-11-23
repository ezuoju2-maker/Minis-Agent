package com.openminis.app.ui.chat

import java.text.BreakIterator

object ThinkingChunking {
    const val CHUNK_LINES = 40

    const val CHUNK_MAX_CHARS = 4000

    const val INITIAL_CHUNKS = 5

    const val INITIAL_CHAR_CAP = 10 * 1024

    const val BATCH_CHUNKS = 5

    const val SLOW_BATCH_MS = 500L

    data class Chunk(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    data class Pacing(val batch: Int = BATCH_CHUNKS, val auto: Boolean = true)

    fun chunks(text: String, lines: Int = CHUNK_LINES, maxChars: Int = CHUNK_MAX_CHARS): List<Chunk> {
        require(lines > 0 && maxChars > 0)
        if (text.isEmpty()) return emptyList()
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        val out = ArrayList<Chunk>()
        var pos = 0
        while (pos < text.length) {
            val limit = minOf(text.length, pos + maxChars)
            var end = -1
            var seen = 0
            var i = pos
            while (i < limit) {
                if (text[i] == '\n' && ++seen == lines) {
                    end = i + 1
                    break
                }
                i++
            }
            if (end < 0) {
                end = if (limit == text.length) {
                    limit
                } else {
                    val nl = text.lastIndexOf('\n', limit - 1)
                    if (nl >= pos) nl + 1 else graphemeCut(text, graphemes, pos, limit)
                }
            }
            out += Chunk(pos, end)
            pos = end
        }
        return out
    }

    private fun graphemeCut(text: String, graphemes: BreakIterator, pos: Int, limit: Int): Int {
        var b = if (graphemes.isBoundary(limit)) limit else graphemes.preceding(limit)
        while (b > pos && joined(text, b)) b = graphemes.preceding(b)
        if (b > pos) return b
        b = graphemes.following(pos)
        while (b < text.length && joined(text, b)) b = graphemes.following(b)
        return b
    }

    private fun joined(text: String, at: Int): Boolean {
        if (at <= 0 || at >= text.length) return false
        val next = text.codePointAt(at)
        return text[at - 1] == '\u200D' ||
            next == 0x200D ||
            next in 0xFE00..0xFE0F ||
            next in 0x1F3FB..0x1F3FF ||
            next in 0xE0020..0xE007F
    }

    fun chunkText(text: String, chunks: List<Chunk>, index: Int): String =
        chunks[index].let { text.substring(it.start, it.end) }

    fun initialCount(chunks: List<Chunk>): Int {
        if (chunks.isEmpty()) return 0
        var count = 0
        var chars = 0
        for (c in chunks.take(INITIAL_CHUNKS)) {
            chars += c.length
            count++
            if (chars >= INITIAL_CHAR_CAP) break
        }
        return count
    }

    fun nextCount(revealed: Int, total: Int, pacing: Pacing = Pacing()): Int =
        minOf(total, revealed + pacing.batch)

    fun hasMore(revealed: Int, total: Int): Boolean = revealed < total

    fun renderedRange(chunks: List<Chunk>, revealed: Int): IntRange =
        if (revealed <= 0 || chunks.isEmpty()) IntRange.EMPTY else 0 until chunks[minOf(revealed, chunks.size) - 1].end

    fun paceAfter(pacing: Pacing, frameMs: Long): Pacing =
        if (frameMs >= SLOW_BATCH_MS) Pacing(batch = 1, auto = false) else pacing

    fun copyText(full: String): String = full
}
