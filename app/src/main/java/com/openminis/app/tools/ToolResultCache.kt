package com.openminis.app.tools

import org.json.JSONObject

object ToolResultCache {

    val READ_ONLY: Set<String> = setOf(
        FileReadTool.NAME,
        RepoMapTool.NAME,
        SkillLoadTool.NAME,
    )

    val WRITERS: Set<String> = setOf(
        "shell_execute",
        FileWriteTool.NAME,
        FileEditTool.NAME,
        ApplyPatchTool.NAME,
        SkillSaveTool.NAME,
    )

    const val HIT_NOTE: String = "\n[unchanged: this read repeats an earlier identical call and nothing has been" +
        " written since. The content above comes from that earlier read; call again only if you suspect an" +
        " edit made outside this session.]"

    private const val DEFAULT_TTL_MS = 300_000L
    private const val MAX_ENTRIES = 16
    private const val MAX_BYTES = 2_000_000
    private const val MAX_SINGLE_CHARS = 200_000

    private class Entry(val output: String, val atMs: Long, val chars: Int)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalChars = 0

    fun isReadOnly(name: String): Boolean = name in READ_ONLY

    fun isWriter(name: String): Boolean = name in WRITERS

    fun keyFor(name: String, argsJson: String): String? {
        if (name !in READ_ONLY) return null
        val canonical = try {
            val obj = JSONObject(argsJson)
            obj.remove("tool_title")
            obj.remove("description")
            val keys = obj.keys().asSequence().toList().sorted()
            keys.joinToString("&") { k -> k + "=" + obj.opt(k).toString() }
        } catch (_: Exception) {
            argsJson.trim()
        }
        return name + "|" + canonical
    }

    @Synchronized
    fun get(key: String, now: Long = System.currentTimeMillis(), ttlMs: Long = DEFAULT_TTL_MS): String? {
        val entry = entries[key] ?: return null
        if (now - entry.atMs > ttlMs) {
            entries.remove(key)
            totalChars -= entry.chars
            return null
        }
        return entry.output
    }

    @Synchronized
    fun put(key: String, output: String, now: Long = System.currentTimeMillis()) {
        if (output.length > MAX_SINGLE_CHARS) return
        val old = entries.put(key, Entry(output, now, output.length))
        if (old != null) totalChars -= old.chars
        totalChars += output.length
        trim()
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while ((entries.size > MAX_ENTRIES || totalChars > MAX_BYTES) && iterator.hasNext()) {
            val e = iterator.next()
            totalChars -= e.value.chars
            iterator.remove()
        }
    }

    @Synchronized
    fun invalidateAll() {
        entries.clear()
        totalChars = 0
    }

    @Synchronized
    fun size(): Int = entries.size
}
