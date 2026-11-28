package com.openminis.app.memory.retrieval

import kotlin.math.sqrt

class HybridMemoryIndex(
    private val vectorDims: Int = 128,
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
    private val lexicalWeight: Double = 0.65,
) {

    data class Doc(val id: String, val text: String)

    data class Hit(val id: String, val score: Double, val lexical: Double, val vector: Double)

    private class Entry(val text: String, val terms: List<String>, val tf: Map<String, Int>, val len: Int, val vec: DoubleArray)

    private val entries = LinkedHashMap<String, Entry>()
    private val df = HashMap<String, Int>()
    private var totalLen = 0L

    val size: Int get() = entries.size

    @Synchronized
    fun upsert(id: String, text: String) {
        remove(id)
        val terms = tokenize(text)
        if (terms.isEmpty()) return
        val tf = HashMap<String, Int>(terms.size)
        for (t in terms) tf[t] = (tf[t] ?: 0) + 1
        for (t in tf.keys) df[t] = (df[t] ?: 0) + 1
        totalLen += terms.size
        entries[id] = Entry(text, terms, tf, terms.size, vectorize(terms))
    }

    @Synchronized
    fun remove(id: String) {
        val old = entries.remove(id) ?: return
        for (t in old.tf.keys) {
            val n = (df[t] ?: 0) - 1
            if (n <= 0) df.remove(t) else df[t] = n
        }
        totalLen -= old.len
    }

    @Synchronized
    fun clear() {
        entries.clear()
        df.clear()
        totalLen = 0
    }

    @Synchronized
    fun search(query: String, limit: Int = 8, minScore: Double = 0.0): List<Hit> {
        if (entries.isEmpty() || limit <= 0) return emptyList()
        val qTerms = tokenize(query)
        if (qTerms.isEmpty()) return emptyList()
        val qTf = HashMap<String, Int>(qTerms.size)
        for (t in qTerms) qTf[t] = (qTf[t] ?: 0) + 1
        val qVec = vectorize(qTerms)
        val avgdl = if (entries.isEmpty()) 1.0 else (totalLen.toDouble() / entries.size).coerceAtLeast(1.0)
        val n = entries.size.toDouble()

        val hits = ArrayList<Hit>(entries.size)
        for ((id, e) in entries) {
            var lex = 0.0
            for ((t, qc) in qTf) {
                val f = e.tf[t] ?: continue
                val d = df[t] ?: 0
                val idf = kotlin.math.ln(1.0 + (n - d + 0.5) / (d + 0.5))
                lex += idf * (f * (k1 + 1.0)) / (f + k1 * (1.0 - b + b * e.len / avgdl)) * qc.toDouble()
            }
            if (lex <= 0.0) continue
            val cos = cosine(qVec, e.vec)
            val score = lexicalWeight * lex + (1.0 - lexicalWeight) * cos
            if (score >= minScore) hits.add(Hit(id, score, lex, cos))
        }
        hits.sortWith { a, b2 ->
            if (a.score != b2.score) b2.score.compareTo(a.score) else a.id.compareTo(b2.id)
        }
        return if (hits.size > limit) hits.subList(0, limit).toList() else hits
    }

    @Synchronized
    fun rrfFuse(resultSets: List<List<String>>, k: Int = 60, limit: Int = 8): List<String> {
        val score = HashMap<String, Double>()
        for (set in resultSets) {
            for ((rank, id) in set.withIndex()) {
                score[id] = (score[id] ?: 0.0) + 1.0 / (k + rank + 1)
            }
        }
        val ranked = score.entries.sortedWith { a, b2 ->
            if (a.value != b2.value) b2.value.compareTo(a.value) else a.key.compareTo(b2.key)
        }
        return ranked.take(limit).map { it.key }
    }

    private fun tokenize(text: String): List<String> = MemoryTokens.of(text)

    private fun vectorize(terms: List<String>): DoubleArray {
        val v = DoubleArray(vectorDims)
        for (t in terms) {
            val h = fnv64(t)
            val idx = java.lang.Math.floorMod(h xor (h ushr 32), vectorDims.toLong()).toInt()
            val sign = if ((h ushr 63) == 0L) 1.0 else -1.0
            v[idx] += sign
        }
        return normalize(v)
    }

    private fun normalize(v: DoubleArray): DoubleArray {
        var sq = 0.0
        for (x in v) sq += x * x
        if (sq <= 0.0) return v
        val inv = 1.0 / sqrt(sq)
        for (i in v.indices) v[i] *= inv
        return v
    }

    private fun cosine(a: DoubleArray, b2: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b2[i]
        return s
    }

    private fun fnv64(s: String): Long {
        var h = -3750763034362895579L
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

}
