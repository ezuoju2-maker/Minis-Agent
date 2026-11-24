package com.openminis.app.sandbox.checkpoint

data class RestoreOp(val path: String, val content: String?, val truncated: Boolean = false)

class SessionCheckpoint(
    private val maxFiles: Int = 200,
    private val maxBytes: Int = 4 * 1024 * 1024,
) {

    private data class Entry(
        val path: String,
        val before: String?,
        var after: String,
        val firstTurn: Int,
        var lastTurn: Int,
        var perFileTruncated: Boolean,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var bytes = 0

    val size: Int get() = entries.size

    @Synchronized
    fun record(turn: Int, path: String, before: String?, after: String): Boolean {
        if (before == after) return false
        val existing = entries[path]
        if (existing != null) {
            bytes += (after.length - existing.after.length)
            existing.after = after
            existing.lastTurn = turn
            trim()
            return true
        }
        if (maxFiles <= 0 || maxBytes <= 0) return false
        var text = after
        var truncated = false
        val cap = maxBytes / maxOf(1, maxFiles)
        if (text.length > cap) {
            text = text.take(cap)
            truncated = true
        }
        bytes += text.length + (before?.length ?: 0)
        entries[path] = Entry(path, before, text, turn, turn, truncated)
        trim()
        return true
    }

    @Synchronized
    fun changedPaths(): List<String> = entries.keys.toList()

    @Synchronized
    fun restorePlan(): List<RestoreOp> {
        val plan = ArrayList<RestoreOp>(entries.size)
        for (e in entries.values) {
            plan.add(RestoreOp(e.path, e.before, e.perFileTruncated))
        }
        return plan
    }

    @Synchronized
    fun revertPlan(path: String): RestoreOp? = entries[path]?.let { RestoreOp(it.path, it.before, it.perFileTruncated) }

    @Synchronized
    fun forget(path: String): Boolean {
        val e = entries.remove(path) ?: return false
        bytes -= e.after.length + (e.before?.length ?: 0)
        if (bytes < 0) bytes = 0
        return true
    }

    @Synchronized
    fun markRestored() = clear()

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    @Synchronized
    fun summary(): String {
        if (entries.isEmpty()) return "no changes recorded"
        val first = entries.values.minOf { it.firstTurn }
        val last = entries.values.maxOf { it.lastTurn }
        return "${entries.size} file(s) changed across turns $first..$last"
    }

    private fun trim() {
        while (entries.size > maxFiles || bytes > maxBytes) {
            val oldest = entries.keys.firstOrNull() ?: return
            val e = entries.remove(oldest) ?: return
            bytes -= e.after.length + (e.before?.length ?: 0)
            if (bytes < 0) bytes = 0
        }
    }
}
