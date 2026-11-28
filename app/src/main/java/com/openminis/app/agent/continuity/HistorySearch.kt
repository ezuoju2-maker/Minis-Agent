package com.openminis.app.agent.continuity

object HistorySearch {

    const val EXCERPT_CHARS = 600
    private const val LEAD = 100

    data class Record(val id: String, val role: String, val text: String)

    data class Hit(
        val id: String,
        val role: String,
        val excerpt: String,
        val excerptStart: Int,
        val excerptEnd: Int,
        val matched: Boolean,
        val position: Int,
    )

    fun search(
        records: List<Record>,
        query: String,
        role: String? = null,
        latestFirst: Boolean = false,
        limit: Int = 8,
    ): List<Hit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val filtered = records.filter { role.isNullOrEmpty() || role == "all" || it.role == role }
        val ordered = if (latestFirst) filtered.asReversed() else filtered
        val out = ArrayList<Hit>()
        for (r in ordered) {
            val hit = locate(r.text, needle) ?: continue
            out.add(
                Hit(
                    id = r.id,
                    role = r.role,
                    excerpt = hit.first,
                    excerptStart = hit.second,
                    excerptEnd = hit.third,
                    matched = true,
                    position = out.size,
                ),
            )
            if (out.size >= limit.coerceIn(1, 20)) break
        }
        return out
    }

    fun read(records: List<Record>, id: String, offset: Int, limit: Int): Pair<Record, String>? {
        val record = records.firstOrNull { it.id == id } ?: return null
        val start = offset.coerceIn(0, record.text.length)
        val end = (start + limit.coerceIn(1, 4000)).coerceAtMost(record.text.length)
        return record to record.text.substring(start, end)
    }

    private fun locate(text: String, needle: String): Triple<String, Int, Int>? {
        if (text.isEmpty()) return null
        val index = text.indexOf(needle, ignoreCase = true)
        if (index < 0) return null
        val start = (index - LEAD).coerceAtLeast(0)
        val end = (start + EXCERPT_CHARS).coerceAtMost(text.length).coerceAtLeast(index + needle.length)
        val excerpt = text.substring(start, end)
        return Triple(excerpt, start, end)
    }
}
