package com.openminis.app.ui.chat.search

import java.text.Normalizer
import java.util.Locale


data class ChatSearchUnitKey(val rowKey: String, val field: String)

data class ChatSearchUnit(val key: ChatSearchUnitKey, val text: String)

data class ChatSearchMatch(val key: ChatSearchUnitKey, val ordinal: Int)

object ChatSearchMatcher {
    fun normalizedQuery(raw: String): String = raw.trim()

    fun isInvisible(c: Char): Boolean =
        c == '​' || c == ' ' || c == ' ' || c == '￼' || c == '﻿'

    private class Folded(val text: String, val start: IntArray, val end: IntArray)

    private val foldCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private const val FOLD_CACHE_MAX = 16_384

    private fun foldCodePoint(cp: Int): String {
        foldCache[cp]?.let { return it }
        val nfd = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        val base = StringBuilder(nfd.length)
        var k = 0
        while (k < nfd.length) {
            val q = nfd.codePointAt(k)
            if (Character.getType(q) != Character.NON_SPACING_MARK.toInt()) base.appendCodePoint(q)
            k += Character.charCount(q)
        }
        val folded = base.toString().lowercase(Locale.ROOT)
        if (foldCache.size < FOLD_CACHE_MAX) foldCache[cp] = folded
        return folded
    }

    private fun fold(text: String): Folded {
        val out = StringBuilder(text.length)
        var starts = IntArray(text.length.coerceAtLeast(1))
        var ends = IntArray(starts.size)
        var count = 0
        fun push(start: Int, end: Int) {
            if (count == starts.size) {
                starts = starts.copyOf(count * 2)
                ends = ends.copyOf(count * 2)
            }
            starts[count] = start
            ends[count] = end
            count++
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val c0 = text[i]
            if (n == 1 && isInvisible(c0)) {
                i += n
                continue
            }
            if (cp < 0x80) {
                out.append(if (c0 in 'A'..'Z') c0 + 32 else c0)
                push(i, i + n)
            } else {
                for (ch in foldCodePoint(cp)) {
                    out.append(ch)
                    push(i, i + n)
                }
            }
            i += n
        }
        return Folded(out.toString(), starts.copyOf(count), ends.copyOf(count))
    }

    fun foldQuery(query: String): String = fold(query).text

    fun ranges(query: String, text: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
        if (query.isEmpty() || text.isEmpty() || limit <= 0) return emptyList()
        val q = foldQuery(query)
        if (q.isEmpty()) return emptyList()
        val f = fold(text)
        val out = ArrayList<IntRange>()
        var from = 0
        while (from <= f.text.length - q.length) {
            val at = f.text.indexOf(q, from)
            if (at < 0) break
            out.add(f.start[at] until f.end[at + q.length - 1])
            if (out.size >= limit) break
            from = at + q.length
        }
        return out
    }

    fun count(query: String, text: String, limit: Int): Int = ranges(query, text, limit).size
}

data class ChatSearchResult(
    val matches: List<ChatSearchMatch>,
    val countByUnit: Map<ChatSearchUnitKey, Int>,
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = ChatSearchResult(emptyList(), emptyMap(), false)
    }
}

object ChatSearchEngine {
    const val PER_UNIT_LIMIT = 200

    const val TOTAL_LIMIT = 2000

    fun search(
        units: List<ChatSearchUnit>,
        query: String,
        perUnitLimit: Int = PER_UNIT_LIMIT,
        totalLimit: Int = TOTAL_LIMIT,
        isCancelled: () -> Boolean = { false },
    ): ChatSearchResult? {
        val q = ChatSearchMatcher.normalizedQuery(query)
        if (q.isEmpty() || ChatSearchMatcher.foldQuery(q).isEmpty()) return ChatSearchResult.EMPTY
        val matches = ArrayList<ChatSearchMatch>()
        val countByUnit = HashMap<ChatSearchUnitKey, Int>()
        var truncated = false
        for ((i, unit) in units.withIndex()) {
            if (i % 64 == 0 && isCancelled()) return null
            val remaining = totalLimit - matches.size
            if (remaining <= 0) {
                if (ChatSearchMatcher.count(q, unit.text, 1) > 0) {
                    truncated = true
                    break
                }
                continue
            }
            val n = ChatSearchMatcher.count(q, unit.text, minOf(perUnitLimit, remaining + 1))
            if (n <= 0) continue
            val kept = minOf(n, remaining, perUnitLimit)
            if (n > remaining) truncated = true
            countByUnit[unit.key] = kept
            for (ordinal in 0 until kept) matches.add(ChatSearchMatch(unit.key, ordinal))
        }
        return ChatSearchResult(matches, countByUnit, truncated)
    }
}

object ChatSearchNavigation {
    data class Step(val index: Int, val wrapped: Boolean)

    fun initialIndex(count: Int): Int? = if (count > 0) count - 1 else null

    fun step(index: Int?, count: Int, forward: Boolean): Step? {
        if (count <= 0) return null
        if (index == null || index < 0 || index >= count) return Step(if (forward) 0 else count - 1, false)
        if (forward) return if (index + 1 < count) Step(index + 1, false) else Step(0, count > 1)
        return if (index > 0) Step(index - 1, false) else Step(count - 1, count > 1)
    }

    fun relocate(previous: ChatSearchMatch?, previousIndex: Int?, matches: List<ChatSearchMatch>): Int? {
        if (matches.isEmpty()) return null
        if (previous != null) {
            val i = matches.indexOf(previous)
            if (i >= 0) return i
        }
        if (previousIndex == null) return null
        return previousIndex.coerceIn(0, matches.size - 1)
    }

    fun counterText(index: Int?, count: Int, truncated: Boolean): String {
        val total = if (truncated) "$count+" else "$count"
        if (index == null || count <= 0) return "0/$total"
        return "${index + 1}/$total"
    }
}
