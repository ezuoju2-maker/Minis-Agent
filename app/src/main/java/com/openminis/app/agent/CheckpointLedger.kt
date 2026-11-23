package com.openminis.app.agent

import org.json.JSONArray
import org.json.JSONObject

class CheckpointLedger(private val maxStages: Int = 512) {

    data class Stage(
        val key: String,
        val title: String,
        val status: String = "pending",
        val note: String = "",
        val updatedAt: Long = 0L,
    )

    private val stages = LinkedHashMap<String, Stage>()

    init {
        require(maxStages > 0) { "maxStages must be positive" }
    }

    fun record(key: String, title: String, status: String, note: String = "", now: Long = 0L): Boolean {
        val k = key.trim()
        require(k.isNotEmpty()) { "stage key required" }
        val normalized = normalizeStatus(status)
        val old = stages[k]
        stages[k] = Stage(
            key = k,
            title = title.ifEmpty { old?.title ?: k },
            status = normalized,
            note = note.ifEmpty { old?.note ?: "" },
            updatedAt = if (now > 0L) now else (old?.updatedAt ?: 0L),
        )
        if (stages.size > maxStages) {
            val dropCount = stages.size - maxStages
            val iterator = stages.iterator()
            repeat(dropCount) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
        }
        return old == null
    }

    fun get(key: String): Stage? = stages[key]

    fun all(): List<Stage> = stages.values.toList()

    fun nextPending(): Stage? = stages.values.firstOrNull { it.status != "done" }

    val isComplete: Boolean get() = stages.isNotEmpty() && stages.values.all { it.status == "done" }
    val blocked: List<Stage> get() = stages.values.filter { it.status == "blocked" }

    fun nextActionable(): Stage? =
        stages.values.firstOrNull { it.status == "pending" || it.status == "in_progress" }

    fun resumeSummary(): String {
        if (stages.isEmpty()) return "(no checkpoints recorded)"
        val done = stages.values.filter { it.status == "done" }
        val blockedList = blocked
        val next = nextActionable()
        val sb = StringBuilder()
        sb.append("Checkpoints: ${done.size}/${stages.size} done\n")
        if (done.isNotEmpty()) {
            sb.append("Completed:\n")
            done.forEach { s ->
                val suffix = if (s.note.isEmpty()) "" else " — ${s.note}"
                sb.append("- ${s.title}$suffix\n")
            }
        }
        if (blockedList.isNotEmpty()) {
            sb.append("Blocked (needs user action):\n")
            blockedList.forEach { sb.append("- ${it.title}: ${it.note}\n") }
        }
        if (next != null) {
            sb.append("Next: ${next.title}\n")
        } else if (blockedList.isNotEmpty()) {
            sb.append("Next: none — all remaining stages are blocked; ask the user.\n")
        } else {
            sb.append("Next: none — all stages done\n")
        }
        return sb.toString().trimEnd()
    }

    fun toJson(): String {
        val arr = JSONArray()
        stages.values.forEach { s ->
            arr.put(JSONObject()
                .put("key", s.key).put("title", s.title).put("status", s.status)
                .put("note", s.note).put("updatedAt", s.updatedAt))
        }
        return JSONObject().put("stages", arr).toString()
    }

    fun load(json: String): Boolean {
        val parsed = LinkedHashMap<String, Stage>()
        val ok = try {
            val arr = JSONObject(json).optJSONArray("stages") ?: return false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key", "")
                if (key.isEmpty()) continue
                parsed[key] = Stage(
                    key = key,
                    title = o.optString("title", key),
                    status = normalizeStatus(o.optString("status", "pending")),
                    note = o.optString("note", ""),
                    updatedAt = o.optLong("updatedAt", 0L),
                )
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!ok) return false
        stages.clear()
        stages.putAll(parsed)
        return true
    }

    fun toJsonl(): String = buildString {
        stages.values.forEach { s ->
            append(JSONObject()
                .put("key", s.key).put("title", s.title).put("status", s.status)
                .put("note", s.note).put("updatedAt", s.updatedAt).toString())
            append('\n')
        }
    }

    companion object {
        fun normalizeStatus(raw: String): String = when (raw.trim().lowercase()) {
            "done", "completed", "complete", "finished" -> "done"
            "in_progress", "in-progress", "running", "active" -> "in_progress"
            "blocked", "stuck", "failed" -> "blocked"
            else -> "pending"
        }
    }
}
