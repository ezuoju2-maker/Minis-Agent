package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.openminis.app.data.model.ModelEntry
import kotlinx.coroutines.delay

object ModelSearch {

    const val EXACT = 1000
    const val PREFIX = 900
    const val TOKEN_PREFIX = 800
    const val SUBSTRING = 700
    const val SUBSEQUENCE = 100

    const val DEBOUNCE_MS = 120L

    const val MAX_RESULTS = 150

    fun score(text: String, query: String): Int {
        if (query.isEmpty()) return EXACT
        val target = text.lowercase()
        if (target == query) return EXACT
        if (target.startsWith(query)) return PREFIX + lengthBonus(target)
        if (tokenHasPrefix(target, query)) return TOKEN_PREFIX + lengthBonus(target)
        if (target.contains(query)) return SUBSTRING + lengthBonus(target)
        if (isSubsequence(query, target)) return SUBSEQUENCE
        return 0
    }

    fun bestScore(texts: List<String>, query: String): Int {
        var best = 0
        for (t in texts) {
            val s = score(t, query)
            if (s > best) best = s
            if (best >= EXACT) break
        }
        return best
    }

    fun normalize(raw: String): String = raw.trim().lowercase()

    private fun lengthBonus(target: String): Int = maxOf(0, 99 - minOf(99, target.length))

    private fun tokenHasPrefix(target: String, query: String): Boolean {
        var atBoundary = true
        for (i in target.indices) {
            if (atBoundary && target.startsWith(query, i)) return true
            val ch = target[i]
            atBoundary = ch == '-' || ch == '/' || ch == '.' || ch == '_' || ch == ':' || ch == ' '
        }
        return false
    }

    private fun isSubsequence(query: String, target: String): Boolean {
        var idx = 0
        for (ch in query) {
            val found = target.indexOf(ch, idx)
            if (found < 0) return false
            idx = found + 1
        }
        return true
    }

    fun rank(entries: List<ModelEntry>, query: String): List<ModelEntry> {
        if (query.isEmpty()) return entries
        val scored = ArrayList<Pair<ModelEntry, Int>>()
        for (e in entries) {
            val s = bestScore(listOf(e.model.displayName, e.model.id), query)
            if (s > 0) scored += e to s
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    data class Section<K>(val key: K, val entries: List<ModelEntry>)

    data class Result<K>(
        val sections: List<Section<K>>,
        val totalMatches: Int,
    ) {
        val shown: Int get() = sections.sumOf { it.entries.size }
        val truncated: Boolean get() = totalMatches > shown
    }

    fun <K> search(
        sections: List<Pair<K, List<ModelEntry>>>,
        label: (K) -> String,
        query: String,
        maxResults: Int = MAX_RESULTS,
    ): Result<K> {
        if (query.isEmpty()) {
            val all = sections.filter { it.second.isNotEmpty() }.map { Section(it.first, it.second) }
            return Result(all, all.sumOf { it.entries.size })
        }
        val matched = sections.mapNotNull { (key, entries) ->
            val hit = if (score(label(key), query) > 0) entries else rank(entries, query)
            if (hit.isEmpty()) null else Section(key, hit)
        }
        val total = matched.sumOf { it.entries.size }
        var remaining = maxResults
        val capped = ArrayList<Section<K>>()
        for (s in matched) {
            if (remaining <= 0) break
            if (s.entries.size <= remaining) {
                capped += s
                remaining -= s.entries.size
            } else {
                capped += Section(s.key, s.entries.take(remaining))
                remaining = 0
            }
        }
        return Result(capped, total)
    }
}

@Composable
fun rememberDebouncedQuery(raw: String, delayMs: Long = ModelSearch.DEBOUNCE_MS): String {
    val normalized = ModelSearch.normalize(raw)
    var debounced by remember { mutableStateOf(normalized) }
    LaunchedEffect(normalized) {
        if (normalized.isNotEmpty()) delay(delayMs)
        debounced = normalized
    }
    return if (normalized.isEmpty()) "" else debounced
}
