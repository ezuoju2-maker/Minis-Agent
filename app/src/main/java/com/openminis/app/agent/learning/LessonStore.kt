package com.openminis.app.agent.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LessonStore(context: Context) {

    data class Lesson(
        val id: String,
        val text: String,
        val tags: List<String>,
        val confidence: Double,
        val uses: Int,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("text", text)
            put("tags", JSONArray(tags))
            put("confidence", confidence)
            put("uses", uses)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }

        companion object {
            fun from(o: JSONObject): Lesson? {
                val text = o.optString("text", "").trim()
                if (text.isEmpty()) return null
                val arr = o.optJSONArray("tags")
                val tags = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it, "") }
                    .filter { it.isNotEmpty() }
                return Lesson(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    text = text,
                    tags = tags,
                    confidence = o.optDouble("confidence", 0.6).coerceIn(0.0, 1.0),
                    uses = o.optInt("uses", 0),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                )
            }
        }
    }

    private val file = File(context.filesDir, "learning/lessons.jsonl")
    private val lock = Any()

    fun all(): List<Lesson> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { Lesson.from(JSONObject(line)) }.getOrNull() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(rawText: String, tags: List<String> = emptyList()): Lesson? {
        val text = Companion.clean(rawText)
        if (text.length < 3 || text.length > 240) return null
        synchronized(lock) {
            val current = all().toMutableList()
            val near = current.firstOrNull { Companion.similarity(it.text, text) >= DEDUPE }
            if (near != null) {
                val merged = near.copy(
                    text = if (text.length > near.text.length) text else near.text,
                    confidence = (near.confidence + 0.15).coerceAtMost(1.0),
                    uses = near.uses + 1,
                    updatedAt = System.currentTimeMillis(),
                    tags = (near.tags + tags.map { it.lowercase() }).distinct(),
                )
                current[current.indexOf(near)] = merged
                write(current)
                return merged
            }
            val lesson = Lesson(
                id = UUID.randomUUID().toString().take(8),
                text = text,
                tags = tags.map { it.lowercase() }.filter { it.isNotBlank() }.take(6),
                confidence = 0.6,
                uses = 0,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            current.add(lesson)
            write(current.sortedWith(compareByDescending<Lesson> { it.confidence }.thenByDescending { it.updatedAt })
                .take(MAX_LESSONS))
            return lesson
        }
    }

    fun forget(raw: String): Boolean {
        val key = raw.trim()
        if (key.isEmpty()) return false
        synchronized(lock) {
            val current = all()
            val hit = current.firstOrNull { it.id.equals(key, true) }
                ?: current.firstOrNull { Companion.similarity(it.text, Companion.clean(key)) >= 0.5 }
                ?: return false
            write(current.filter { it.id != hit.id })
            return true
        }
    }

    fun markUsed(lessons: List<Lesson>) {
        if (lessons.isEmpty()) return
        synchronized(lock) {
            val current = all().toMutableList()
            for (l in lessons) {
                val idx = current.indexOfFirst { it.id == l.id }
                if (idx >= 0) current[idx] = current[idx].copy(uses = current[idx].uses + 1)
            }
            write(current)
        }
    }

    fun search(query: String, limit: Int): List<Lesson> {
        val q = Companion.clean(query)
        if (q.isEmpty()) return emptyList()
        val all = all()
        return all
            .map { it to Companion.relevance(q, it.text + " " + it.tags.joinToString(" ")) }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<Lesson, Double>> { it.second * it.first.confidence }
                    .thenByDescending { it.first.updatedAt },
            )
            .take(limit.coerceIn(1, 8))
            .map { it.first }
    }

    private fun write(lessons: List<Lesson>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "lessons.tmp")
            tmp.writeText(lessons.joinToString("\n") { it.toJson().toString() })
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_LESSONS = 200
        const val DEDUPE = 0.72

        fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()

        fun tokens(text: String): Set<String> {
            val lower = clean(text).lowercase()
            val latin = Regex("[a-z0-9_]+").findAll(lower).map { it.value }.filter { it.length >= 2 }.toMutableSet()
            val cjk = lower.filter { it.code in 0x4E00..0x9FFF }
            for (i in 0 until cjk.length - 1) latin.add(cjk.substring(i, i + 2))
            return latin
        }

        fun similarity(a: String, b: String): Double {
            val ta = tokens(a)
            val tb = tokens(b)
            if (ta.isEmpty() || tb.isEmpty()) return 0.0
            val inter = ta.intersect(tb).size
            val union = ta.union(tb).size
            return if (union == 0) 0.0 else inter.toDouble() / union
        }

        fun relevance(query: String, haystack: String): Double {
            val tq = tokens(query)
            val th = tokens(haystack)
            if (tq.isEmpty() || th.isEmpty()) return 0.0
            val hit = tq.intersect(th).size
            if (hit == 0) return 0.0
            val coverage = hit.toDouble() / minOf(tq.size, 6)
            return coverage.coerceAtMost(2.0)
        }
    }
}
