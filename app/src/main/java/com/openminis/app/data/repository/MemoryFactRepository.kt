package com.openminis.app.data.repository

import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * [self-improvement / structured-memory] Durable, structured facts extracted
 * from conversations, shared across sessions via the global memory directory
 * (which SessionMounts already mounts into every sandbox at /var/minis/memory
 * and which BackupExporter/Importer already cover).
 *
 * Unlike the free-text daily logs (MemoryRepository), a fact has a stable
 * identity (subject + predicate). Writing the same fact again STRENGTHENS it
 * (occurrences++, confidence up, updatedAt refreshed) instead of appending a
 * duplicate — this is what makes the agent "get wiser with use": a preference
 * stated twice outranks one mentioned once, and conflicting values are
 * resolved by confidence then recency.
 *
 * Storage is JSONL (one fact per line) rather than a Room table: the facts
 * stay visible/editable as a file like the rest of memory, need no schema
 * migration, and the expected scale (hundreds to a few thousand) is fine for
 * an O(n) scan with no FTS/embedding dependency.
 */
class MemoryFactRepository(private val memoryDir: File) {

    data class Fact(
        val subject: String,
        val predicate: String,
        val value: String,
        val confidence: Float = 0.5f,
        val occurrences: Int = 1,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val sourceSessionId: String = "",
    ) {
        /** Stable identity for upsert / conflict resolution. */
        val key: String get() = "${subject.lowercase(Locale.US)}\u0000${predicate.lowercase(Locale.US)}"
    }

    companion object {
        private const val TAG = "MemoryFactRepo"
        private const val FILE_NAME = "facts.jsonl"
        private const val CAPACITY = 2_000
        private const val MAX_CONFIDENCE = 1.0f
        private const val CONFIDENCE_STEP = 0.15f
        private const val MAX_INJECT_CHARS = 2_000
        /** [advanced-recall] A fact's recency weight halves every 30 days. */
        private const val HALF_LIFE_DAYS = 30.0
    }

    private val file: File get() = File(memoryDir, FILE_NAME)

    init {
        memoryDir.mkdirs()
    }

    /** Serialize-anywhere guard: all mutations go through the monitor. */
    private val lock = Any()

    // -- read --

    private fun loadAllLocked(): List<Fact> {
        val f = file
        if (!f.exists()) return emptyList()
        val out = ArrayList<Fact>()
        f.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val o = JSONObject(line)
                Fact(
                    subject = o.optString("subject", ""),
                    predicate = o.optString("predicate", ""),
                    value = o.optString("value", ""),
                    confidence = o.optDouble("confidence", 0.5).toFloat(),
                    occurrences = o.optInt("occurrences", 1),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    sourceSessionId = o.optString("sourceSessionId", ""),
                )
            }.getOrNull()?.takeIf { it.subject.isNotEmpty() && it.predicate.isNotEmpty() && it.value.isNotEmpty() }
                ?.let { out.add(it) }
        }
        return out
    }

    private fun persistLocked(facts: List<Fact>) {
        val bounded = if (facts.size > CAPACITY) {
            facts.sortedByDescending { it.updatedAt }.take(CAPACITY)
        } else facts
        val tmp = File(memoryDir, "$FILE_NAME.tmp")
        val sb = StringBuilder()
        for (f in bounded) {
            sb.append(
                JSONObject()
                    .put("subject", f.subject).put("predicate", f.predicate).put("value", f.value)
                    .put("confidence", f.confidence.toDouble()).put("occurrences", f.occurrences)
                    .put("createdAt", f.createdAt).put("updatedAt", f.updatedAt)
                    .put("sourceSessionId", f.sourceSessionId).toString(),
            ).append('\n')
        }
        tmp.writeText(sb.toString())
        // Atomic-ish replace so a crash mid-write cannot corrupt all facts.
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    /**
     * Upsert a fact. Re-stating an existing (subject,predicate) strengthens it;
     * a new value for the same key keeps the higher-confidence one and decays
     * the weaker, so a confidently-known preference is not erased by a stray
     * contradictory mention.
     */
    fun upsert(
        subject: String,
        predicate: String,
        value: String,
        sourceSessionId: String = "",
    ): String = synchronized(lock) {
        val s = subject.trim()
        val p = predicate.trim()
        val v = value.trim()
        if (s.isEmpty() || p.isEmpty() || v.isEmpty()) {
            return@synchronized "Error: subject, predicate and value are required"
        }
        val facts = loadAllLocked().toMutableList()
        val now = System.currentTimeMillis()
        val idx = facts.indexOfFirst { it.key == "${
            s.lowercase(Locale.US)
        }\u0000${p.lowercase(Locale.US)}" }
        if (idx >= 0) {
            val old = facts[idx]
            if (old.value.equals(v, ignoreCase = true)) {
                facts[idx] = old.copy(
                    occurrences = old.occurrences + 1,
                    confidence = minOf(MAX_CONFIDENCE, old.confidence + CONFIDENCE_STEP),
                    updatedAt = now,
                )
            } else if (v.length > old.value.length && old.confidence < 0.6f) {
                // A richer, weakly-held value supersedes a tentative one.
                facts[idx] = old.copy(
                    value = v, occurrences = old.occurrences + 1,
                    confidence = minOf(MAX_CONFIDENCE, old.confidence + CONFIDENCE_STEP),
                    updatedAt = now,
                )
            } else {
                // Keep the incumbent; a repeated contradiction erodes it a little.
                facts[idx] = old.copy(
                    confidence = maxOf(0.1f, old.confidence - CONFIDENCE_STEP / 2),
                    occurrences = old.occurrences + 1,
                    updatedAt = now,
                )
            }
        } else {
            facts.add(Fact(s, p, v, sourceSessionId = sourceSessionId, createdAt = now, updatedAt = now))
        }
        persistLocked(facts)
        "Fact remembered: $s · $p = $v"
    }

    /** Keyword search ranked by confidence × recency. */
    fun search(keywords: String, limit: Int = 10): String = synchronized(lock) {
        val terms = keywords.trim().lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val facts = loadAllLocked()
        val matched = if (terms.isEmpty()) {
            facts.sortedByDescending { it.confidence }
        } else {
            facts.filter { f ->
                val hay = "${f.subject} ${f.predicate} ${f.value}".lowercase(Locale.US)
                terms.all { hay.contains(it) }
            }.sortedByDescending { it.confidence * 1000 + it.occurrences + recencyBoost(it.updatedAt) }
        }
        if (matched.isEmpty()) return@synchronized "No remembered facts matched."
        val sb = StringBuilder()
        matched.take(limit.coerceIn(1, 50)).forEach { f ->
            sb.append("- ${f.subject}: ${f.predicate} = ${f.value} (confidence ${"%.2f".format(f.confidence)}, seen ${f.occurrences}x)\n")
        }
        sb.toString().trim()
    }

    /** High-value facts to auto-inject into the system prompt (bounded chars). */
    fun topFacts(limit: Int = 12): String = synchronized(lock) {
        // [advanced-recall] Rank by confidence × recency so a fact reinforced
        // recently outranks an equally-confident but stale one — the "wiser
        // with use" signal reaches the prompt, not just search().
        // IMPORTANT: the ordering uses only each fact's OWN fields (no
        // System.currentTimeMillis()), so for a given fact set the output is
        // byte-stable across requests — required by prompt caching. recencyBoost
        // is anchored to a fixed epoch, not to "now", for exactly this reason.
        val ranked = loadAllLocked()
            .filter { it.confidence >= 0.6f }
            .sortedByDescending { it.confidence * 1000 + it.occurrences + recencyBoost(it.updatedAt) }
            .take(limit)
        if (ranked.isEmpty()) return@synchronized ""
        val sb = StringBuilder()
        for (f in ranked) {
            val line = "- ${f.subject}: ${f.predicate} = ${f.value}\n"
            if (sb.length + line.length > MAX_INJECT_CHARS) break
            sb.append(line)
        }
        sb.toString().trim()
    }

    fun count(): Int = synchronized(lock) { loadAllLocked().size }

    // ---------------------------------------------------------------------
    // [advanced-recall] Hybrid, weighted recall + temporal decay. New API only;
    // the original search()/topFacts() semantics are untouched so all existing
    // callers and tests keep their exact behaviour.
    // ---------------------------------------------------------------------

    data class ScoredFact(val fact: Fact, val score: Double)

    /**
     * Ranked recall with field weighting and fractional term overlap.
     *
     * Differences from the legacy [search]:
     *  - a query matches on ANY term (not ALL), scored by how many terms hit;
     *  - subject / predicate matches weigh more than value matches;
     *  - confidence is multiplied by an exponential recency decay (half-life
     *    [HALF_LIFE_DAYS]) so a once-strong but stale fact sinks below a
     *    recently reinforced one — "wiser with use" without unbounded growth;
     *  - ties break deterministically by key for reproducible injection.
     *
     * The returned strings are the same `- subject: predicate = value` lines
     * used by [search], safe to inject verbatim.
     */
    fun recall(query: String, limit: Int = 10): String = synchronized(lock) {
        val terms = query.trim().lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val facts = loadAllLocked()
        if (terms.isEmpty()) return@synchronized ""

        val now = System.currentTimeMillis()
        val scored = facts.map { f ->
            val s = f.subject.lowercase(Locale.US)
            val p = f.predicate.lowercase(Locale.US)
            val v = f.value.lowercase(Locale.US)
            var hits = 0.0
            for (t in terms) {
                val inS = s.contains(t)
                val inP = p.contains(t)
                val inV = v.contains(t)
                if (inS || inP || inV) hits += 1.0
                if (inS) hits += 0.5   // identity fields weigh more
                if (inP) hits += 0.5
            }
            val coverage = hits / terms.size
            val decay = decayFactor(f.updatedAt, now)
            val score = coverage * f.confidence * decay
            ScoredFact(f, score)
        }.filter { it.score > 0.0 }
            .sortedWith(compareByDescending<ScoredFact> { it.score }.thenBy { it.fact.key })

        if (scored.isEmpty()) return@synchronized ""
        val sb = StringBuilder()
        scored.take(limit.coerceIn(1, 50)).forEach { sf ->
            val f = sf.fact
            sb.append("- ${f.subject}: ${f.predicate} = ${f.value} (confidence ${"%.2f".format(f.confidence)}, seen ${f.occurrences}x)\n")
        }
        sb.toString().trim()
    }

    /**
     * Inject the most relevant facts for an arbitrary query, blending base
     * confidence with query relevance and temporal decay, bounded by chars.
     * Complements [topFacts] (which ignores the current query).
     */
    fun recallForPrompt(query: String, limit: Int = 8, maxChars: Int = MAX_INJECT_CHARS): String =
        synchronized(lock) {
            if (query.isBlank()) return@synchronized topFacts(limit)
            val terms = query.lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val now = System.currentTimeMillis()
            loadAllLocked()
                .filter { it.confidence >= 0.45f }
                .map { f ->
                    val hay = "${f.subject} ${f.predicate} ${f.value}".lowercase(Locale.US)
                    val overlap = if (terms.isEmpty()) 1.0 else terms.count { hay.contains(it) }.toDouble() / terms.size
                    f to (f.confidence * decayFactor(f.updatedAt, now) + overlap)
                }
                .sortedWith(compareByDescending<Pair<Fact, Double>> { it.second }.thenBy { it.first.key })
                .take(limit)
                .map { it.first }
                .mapNotNull { f ->
                    val line = "- ${f.subject}: ${f.predicate} = ${f.value}"
                    line.takeIf { it.length <= maxChars }
                }
                .joinToString("\n")
        }

    private fun decayFactor(updatedAt: Long, now: Long): Double {
        if (updatedAt <= 0L) return 0.5
        val days = (now - updatedAt) / 86_400_000.0
        if (days <= 0.0) return 1.0
        return java.lang.Math.pow(0.5, days / HALF_LIFE_DAYS)
    }

    private fun recencyBoost(updatedAt: Long): Double {
        if (updatedAt <= 0L) return 0.0
        val days = (System.currentTimeMillis() - updatedAt) / 86_400_000.0
        return maxOf(0.0, 30.0 - days) // facts touched today get the biggest boost
    }
}
