package com.openminis.app.provider

import com.openminis.app.logging.AppLogger

object ModelReleaseIndex {
    private const val TAG = "ModelReleaseIndex"

    data class Rank(
        val releaseDay: Int?,
        val outputCostPerMTok: Double,
        val contextWindow: Int,
        val displayName: String,
    )

    val comparator: Comparator<Rank> = Comparator { a, b ->
        val ad = a.releaseDay
        val bd = b.releaseDay
        if (ad != bd) {
            return@Comparator when {
                ad == null -> 1
                bd == null -> -1
                else -> bd.compareTo(ad)
            }
        }
        if (a.outputCostPerMTok != b.outputCostPerMTok) {
            return@Comparator b.outputCostPerMTok.compareTo(a.outputCostPerMTok)
        }
        if (a.contextWindow != b.contextWindow) {
            return@Comparator b.contextWindow.compareTo(a.contextWindow)
        }
        a.displayName.compareTo(b.displayName, ignoreCase = true)
    }

    private class Entry(val day: Int, val outputCost: Double?, val context: Int?)

    private var byFullId: Map<String, Entry> = emptyMap()
    private var byTail: Map<String, Entry> = emptyMap()
    private var built = false

    @Synchronized
    private fun ensureIndex() {
        if (built) return
        val full = HashMap<String, Entry>()
        val tail = HashMap<String, Entry>()
        for (provider in ModelsDevApi.registrySnapshot().values) {
            for ((rawId, m) in provider.models) {
                val day = parseReleaseDay(m.releaseDate) ?: continue
                val e = Entry(day, m.outputCost, m.contextWindow)
                val id = rawId.lowercase()
                full[id]?.let { if (it.day >= day) return@let else full[id] = e } ?: run { full[id] = e }
                val t = id.substringAfterLast('/')
                tail[t]?.let { if (it.day >= day) return@let else tail[t] = e } ?: run { tail[t] = e }
            }
        }
        byFullId = full
        byTail = tail
        built = true
        AppLogger.info(TAG, "release index built: full=${full.size} tail=${tail.size}")
    }

    fun warmUp() = ensureIndex()

    @Synchronized
    fun invalidate() {
        built = false
        byFullId = emptyMap()
        byTail = emptyMap()
    }

    fun rank(modelId: String, displayName: String, contextWindow: Int?): Rank {
        ensureIndex()
        val hit = resolve(modelId)
        return Rank(
            releaseDay = hit?.day,
            outputCostPerMTok = hit?.outputCost ?: 0.0,
            contextWindow = contextWindow ?: hit?.context ?: 0,
            displayName = displayName,
        )
    }

    private fun resolve(raw: String): Entry? {
        val cleaned = clean(raw)
        byFullId[cleaned]?.let { return it }

        var tail = stripVendorDotPrefix(cleaned.substringAfterLast('/'))
        byTail[tail]?.let { return it }

        val undated = stripDateSuffix(tail)
        if (undated != tail) byTail[undated]?.let { return it }

        val parts = undated.split('-').toMutableList()
        while (parts.size > 1) {
            parts.removeAt(parts.size - 1)
            byTail[parts.joinToString("-")]?.let { return it }
        }

        return null
    }

    private fun clean(raw: String): String {
        var s = raw.trim().lowercase().substringBefore(':').substringBefore('@')
        for (region in listOf("us.", "eu.", "apac.", "global.")) {
            if (s.startsWith(region)) { s = s.removePrefix(region); break }
        }
        return s
    }

    private fun stripVendorDotPrefix(s: String): String {
        val dot = s.indexOf('.')
        if (dot <= 0) return s
        val head = s.substring(0, dot)
        if (!head.all { it.isLetter() }) return s
        return s.substring(dot + 1)
    }

    private fun stripDateSuffix(s: String): String {
        if (s.length <= 9) return s
        val tail = s.takeLast(9)
        if (tail[0] != '-') return s
        val digits = tail.drop(1)
        if (digits.length != 8 || !digits.all { it.isDigit() } || !digits.startsWith("20")) return s
        return s.dropLast(9)
    }

    fun parseReleaseDay(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split('-')
        if (parts.size != 2 && parts.size != 3) return null
        val year = parts[0].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val day = if (parts.size == 3) {
            parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        } else 1
        return year * 10_000 + month * 100 + day
    }
}
