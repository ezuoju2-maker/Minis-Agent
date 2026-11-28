package com.openminis.app.agent.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LessonStore(context: Context) {

    enum class Kind(val id: String, val file: String, val cap: Int) {
        ERROR("error", "errors.jsonl", 60),
        CORRECTION("correction", "corrections.jsonl", 80),
        BEST("best_practice", "best_practices.jsonl", 60),
        GAP("knowledge_gap", "knowledge_gaps.jsonl", 40),
        ;

        companion object {
            fun parse(raw: String?): Kind = when (raw?.trim()?.lowercase()) {
                "error", "errors", "失败", "错误" -> ERROR
                "best_practice", "best", "practice", "最佳实践" -> BEST
                "knowledge_gap", "gap", "盲区", "知识盲区" -> GAP
                else -> CORRECTION
            }
        }
    }

    enum class Category(val id: String) {
        PREFERENCE("preference"),
        WORKFLOW("workflow"),
        TECHNICAL("technical"),
        IDENTITY("identity"),
        PERSONA("persona"),
        ;

        companion object {
            fun parse(raw: String?): Category? = when (raw?.trim()?.lowercase()) {
                "preference", "偏好", "用户偏好" -> PREFERENCE
                "workflow", "工作流" -> WORKFLOW
                "technical", "技术", "技术经验" -> TECHNICAL
                "identity", "角色", "身份" -> IDENTITY
                "persona", "性格", "信念" -> PERSONA
                else -> null
            }
        }
    }

    data class Lesson(
        val id: String,
        val kind: Kind,
        val category: Category,
        val text: String,
        val tags: List<String>,
        val confidence: Double,
        val hits: Int,
        val uses: Int,
        val promoted: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind.id)
            put("category", category.id)
            put("text", text)
            put("tags", JSONArray(tags))
            put("confidence", confidence)
            put("hits", hits)
            put("uses", uses)
            put("promoted", promoted)
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
                    id = o.optString("id", UUID.randomUUID().toString().take(8)),
                    kind = Kind.parse(o.optString("kind", "correction")),
                    category = Category.parse(o.optString("category", "workflow")) ?: Category.WORKFLOW,
                    text = text,
                    tags = tags,
                    confidence = o.optDouble("confidence", 0.6).coerceIn(0.0, 1.0),
                    hits = o.optInt("hits", 1),
                    uses = o.optInt("uses", 0),
                    promoted = o.optBoolean("promoted", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                )
            }
        }
    }

    private val dir = File(context.filesDir, "learning")
    private val lock = Any()
    private val legacyFile = File(dir, "lessons.jsonl")
    private val migratedMarker = File(dir, ".migrated")

    fun all(): List<Lesson> {
        migrateLegacyIfNeeded()
        val out = ArrayList<Lesson>()
        for (k in Kind.values()) out.addAll(readKind(k))
        return out
    }

    fun allOf(kind: Kind): List<Lesson> = all().filter { it.kind == kind }

    fun add(
        kind: Kind,
        category: Category,
        rawText: String,
        tags: List<String> = emptyList(),
        initialConfidence: Double = 0.6,
    ): Lesson? {
        val text = Companion.clean(rawText)
        if (text.length < 3 || text.length > 240) return null
        synchronized(lock) {
            migrateLegacyIfNeeded()
            val current = readKind(kind).toMutableList()
            val near = current.firstOrNull { Companion.similarity(it.text, text) >= DEDUPE }
            if (near != null) {
                val merged = near.copy(
                    text = if (text.length > near.text.length) text else near.text,
                    confidence = (near.confidence + 0.15).coerceAtMost(1.0),
                    hits = near.hits + 1,
                    updatedAt = System.currentTimeMillis(),
                    tags = (near.tags + tags.map { it.lowercase() }).distinct(),
                    category = if (near.category == Category.WORKFLOW) category else near.category,
                )
                current[current.indexOf(near)] = merged
                writeKind(kind, current)
                return merged
            }
            val lesson = Lesson(
                id = UUID.randomUUID().toString().take(8),
                kind = kind,
                category = category,
                text = text,
                tags = tags.map { it.lowercase() }.filter { it.isNotBlank() }.take(6),
                confidence = initialConfidence.coerceIn(0.3, 0.9),
                hits = 1,
                uses = 0,
                promoted = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            current.add(lesson)
            writeKind(kind, current)
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
            val list = readKind(hit.kind)
            if (list.none { it.id == hit.id }) return false
            writeKind(hit.kind, list.filter { it.id != hit.id })
            return true
        }
    }

    fun promote(id: String): Lesson? {
        synchronized(lock) {
            val current = all()
            val hit = current.firstOrNull { it.id.equals(id.trim(), true) } ?: return null
            val list = readKind(hit.kind)
            val idx = list.indexOfFirst { it.id == hit.id }
            if (idx < 0) return null
            val promoted = list[idx].copy(
                promoted = true,
                confidence = 1.0,
                updatedAt = System.currentTimeMillis(),
            )
            writeKind(hit.kind, list.map { if (it.id == promoted.id) promoted else it })
            return promoted
        }
    }

    fun markUsed(lessons: List<Lesson>) {
        if (lessons.isEmpty()) return
        synchronized(lock) {
            for (l in lessons) {
                val list = readKind(l.kind).toMutableList()
                val idx = list.indexOfFirst { it.id == l.id }
                if (idx >= 0) list[idx] = list[idx].copy(uses = list[idx].uses + 1)
                writeKind(l.kind, list)
            }
        }
    }

    fun search(query: String, limit: Int): List<Lesson> {
        val q = Companion.clean(query)
        if (q.isEmpty()) return emptyList()
        return all()
            .map { it to Companion.relevance(q, it.text + " " + it.tags.joinToString(" ")) }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<Lesson, Double>> { it.second * it.first.confidence * weight(it.first) }
                    .thenByDescending { it.first.promoted }
                    .thenByDescending { it.first.updatedAt },
            )
            .take(limit.coerceIn(1, 8))
            .map { it.first }
    }

    fun pendingPromotions(): List<Lesson> =
        all().filter { !it.promoted && it.hits >= PROMOTE_HITS && it.kind != Kind.ERROR }

    fun stats(): String =
        Kind.values().joinToString(" · ") { k -> "${k.id} ${allOf(k).size}" } + " · promoted ${all().count { it.promoted }}"

    fun consolidate(): Int {
        synchronized(lock) {
            var merged = 0
            for (k in Kind.values()) {
                val list = readKind(k)
                val kept = ArrayList<Lesson>()
                for (item in list.sortedByDescending { it.hits * 10 + it.confidence * 10 }) {
                    val dup = kept.firstOrNull { Companion.similarity(it.text, item.text) >= 0.85 }
                    if (dup == null) {
                        kept.add(item)
                    } else {
                        val idx = kept.indexOf(dup)
                        kept[idx] = dup.copy(
                            hits = dup.hits + item.hits,
                            confidence = (dup.confidence + 0.1).coerceAtMost(1.0),
                            updatedAt = System.currentTimeMillis(),
                        )
                        merged++
                    }
                }
                if (kept.size != list.size) writeKind(k, kept)
            }
            return merged
        }
    }

    private fun readKind(kind: Kind): List<Lesson> {
        val f = File(dir, kind.file)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { Lesson.from(JSONObject(line)) }.getOrNull() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeKind(kind: Kind, lessons: List<Lesson>) {
        try {
            dir.mkdirs()
            val target = File(dir, kind.file)
            if (target.exists() && target.length() > 0) {
                target.copyTo(File(dir, kind.file + ".bak"), overwrite = true)
            }
            val pruned = lessons.sortedWith(
                compareByDescending<Lesson> { it.promoted }.thenByDescending { it.confidence }
                    .thenByDescending { it.updatedAt },
            ).take(kind.cap)
            val tmp = File(dir, kind.file + ".tmp")
            tmp.writeText(pruned.joinToString("\n") { it.toJson().toString() })
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun migrateLegacyIfNeeded() {
        if (migratedMarker.exists() || !legacyFile.exists()) return
        try {
            val lines = legacyFile.readLines().filter { it.isNotBlank() }
            val imported = lines.mapNotNull { line ->
                runCatching { Lesson.from(JSONObject(line)) }.getOrNull()
            }.map { it.copy(kind = Kind.CORRECTION) }
            if (imported.isNotEmpty()) {
                val existing = readKind(Kind.CORRECTION)
                val merged = (existing + imported).distinctBy { Companion.clean(it.text).lowercase() }
                writeKind(Kind.CORRECTION, merged)
            }
            migratedMarker.writeText("1")
        } catch (_: Exception) {
        }
    }

    private fun weight(l: Lesson): Double = when {
        l.promoted -> 1.6
        l.kind == Kind.CORRECTION && l.category == Category.PREFERENCE -> 1.4
        l.kind == Kind.CORRECTION -> 1.2
        l.kind == Kind.BEST -> 1.0
        l.kind == Kind.GAP -> 0.9
        else -> 1.0
    }

    companion object {
        const val DEDUPE = 0.72
        const val PROMOTE_HITS = 3

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
            return (hit.toDouble() / minOf(tq.size, 6)).coerceAtMost(2.0)
        }

        fun shouldPromote(hits: Int, promoted: Boolean): Boolean = !promoted && hits >= PROMOTE_HITS

        fun inferCategory(text: String): Category {
            val t = clean(text).lowercase()
            val preferenceWords = listOf(
                "我喜欢", "我不喜欢", "我偏好", "我习惯", "我来自", "我住在", "我不吃", "我不爱",
                "我讨厌", "忌口", "别带", "别放", "别用", "不要用", "不要辣", "口味",
            )
            val identityWords = listOf("你是", "你的身份", "你叫", "你的名字", "扮演")
            val technicalWords = listOf("api", "命令", "路径", "报错", "编译", "版本", "接口", "函数", "依赖")
            val workflowWords = listOf("流程", "步骤", "先", "然后", "最后", "每次", "以后", "模板", "格式")
            return when {
                preferenceWords.any { t.contains(it) } -> Category.PREFERENCE
                identityWords.any { t.contains(it) } -> Category.IDENTITY
                technicalWords.any { t.contains(it) } -> Category.TECHNICAL
                workflowWords.any { t.contains(it) } -> Category.WORKFLOW
                else -> Category.WORKFLOW
            }
        }

        fun normalizeFailure(tool: String, output: String): String? {
            val text = clean(output)
            if (text.length < 12) return null
            val lower = text.lowercase()
            val signals = listOf("error", "exception", "failed", "failure", "denied", "not found",
                "cannot", "traceback", "exit code", "e: ", "错误", "失败", "拒绝", "找不到")
            if (signals.none { lower.contains(it) }) return null
            val scrubbed = text
                .replace(Regex("0x[0-9a-fA-F]+"), "0x…")
                .replace(Regex("/[\\w./-]{20,}"), matchPath())
                .replace(Regex("\\d{4,}"), "…")
                .take(150)
            return "$tool: $scrubbed"
        }

        private fun matchPath() = "/…"
    }
}
