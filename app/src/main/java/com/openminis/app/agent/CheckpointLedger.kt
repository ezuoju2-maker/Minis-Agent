package com.openminis.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * [long-tasks / checkpointing] A compact, append-only ledger of completed
 * stages for a long-running task. When a long task is compacted or resumed,
 * the agent should not redo stages it already finished, nor silently skip one.
 * This ledger:
 *
 *  - records each stage with a stable key and an artifact/fingerprint note;
 *  - is idempotent (recording the same stage key twice updates, never duplicates);
 *  - computes a resumable summary: what's done, what's next, what's blocked;
 *  - serialises to JSONL so it can live beside the session and survive restarts.
 *
 * Pure logic (org.json only) — no Android/DB dependency, fully unit-testable.
 *
 * v2.0 audit fixes (见《01-源码审计与缺陷清单》):
 *  - [FIX-01] nextPending() 遵循文档返回首个非 done（含 blocked），
 *    nextActionable() 明确排除 blocked，二者不再语义混淆；
 *  - [FIX-02] resumeSummary() 使用 nextActionable()，blocked 阶段单独列出，
 *    全部剩余阶段都 blocked 时明确提示「请用户处理」而不是留空或谎报完成；
 *  - [FIX-03] load() 改为事务式解析：损坏的 JSON 不再清空内存中已有的检查点，
 *    并返回 Boolean 让调用方能够感知失败；
 *  - [FIX-04] 增加有界容量，防止长任务无上限地堆积 stage（内存/序列化膨胀）。
 */
class CheckpointLedger(private val maxStages: Int = 512) {

    data class Stage(
        val key: String,
        val title: String,
        val status: String = "pending",   // pending | in_progress | done | blocked
        val note: String = "",
        val updatedAt: Long = 0L,
    )

    private val stages = LinkedHashMap<String, Stage>()

    init {
        require(maxStages > 0) { "maxStages must be positive" }
    }

    /** Idempotent upsert; returns true when a new stage was added. */
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
        // [FIX-04] Keep the ledger bounded without ever dropping the most
        // recently updated stages. Done stages are the least valuable to keep
        // when the cap is hit, but we keep insertion order for determinism.
        if (stages.size > maxStages) {
            val dropCount = stages.size - maxStages
            val iterator = stages.iterator()
            repeat(dropCount) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
        }
        return old == null
    }

    fun get(key: String): Stage? = stages[key]

    fun all(): List<Stage> = stages.values.toList()

    /**
     * [FIX-01] The first non-DONE stage in insertion order — including blocked
     * ones, as the v1.61 doc promised. The old implementation silently skipped
     * blocked stages, so "what is still outstanding?" could not be answered.
     * Use [nextActionable] when you need the next step the agent may RUN.
     */
    fun nextPending(): Stage? = stages.values.firstOrNull { it.status != "done" }

    val isComplete: Boolean get() = stages.isNotEmpty() && stages.values.all { it.status == "done" }
    val blocked: List<Stage> get() = stages.values.filter { it.status == "blocked" }

    /**
     * [FIX-01] First actionable (non-done, NON-BLOCKED) stage — the true "next"
     * step. The v1.61 implementation returned nextPending() verbatim, so a
     * blocked stage was repeatedly handed to the model as if it were runnable.
     */
    fun nextActionable(): Stage? =
        stages.values.firstOrNull { it.status == "pending" || it.status == "in_progress" }

    /** [FIX-02] A one-screen resumable summary for injection into a compact/resume prompt. */
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

    /**
     * [FIX-03] Transactional load: parse into a temporary map first and only
     * swap it in on success. v1.61 called stages.clear() before parsing, so a
     * truncated/corrupt JSONL file silently destroyed every checkpoint already
     * held in memory. Returns true when the load succeeded.
     */
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

    /** Dump as JSONL (one object per line) for the append-only event log. */
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
