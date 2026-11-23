package com.openminis.app.ui.chat

import com.openminis.app.data.db.FolderMemberTitle

internal object FolderOfferFormat {
    const val TITLES_PER_FOLDER = 3

    const val FETCH_PER_FOLDER = 8
    const val TITLE_LIMIT = 40

    const val MAX_TITLED_FOLDERS = 20

    const val EXAMPLES_NOTE =
        "The quoted titles after \"e.g.\" are recent chats already in that group; " +
            "they only show the group's topic and are not titles for this conversation. "

    data class Recent(val activity: Long, val titles: List<String>)

    fun clean(title: String): String {
        val flat = title
            .replace(Regex("[\"\\u201C\\u201D]"), "'")
            .replace('[', '(').replace(']', ')')
            .replace(';', ',')
            .replace(Regex("[{}\\\\]"), " ")
            .replace(Regex("\\p{Cf}"), "")
            .replace(Regex("[\\s\\u2028\\u2029\\u00A0]+"), " ")
            .trim()
        if (flat.codePointCount(0, flat.length) <= TITLE_LIMIT) return flat
        return flat.substring(0, flat.offsetByCodePoints(0, TITLE_LIMIT - 1)) + "…"
    }

    fun sample(titles: List<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (t in titles) {
            val c = clean(t)
            if (c.isEmpty() || !seen.add(c)) continue
            out += c
            if (out.size == TITLES_PER_FOLDER) break
        }
        return out
    }

    fun lines(ids: List<String>, lines: List<String>, recent: Map<String, Recent>): Pair<List<String>, Int> {
        val titled = ids.withIndex()
            .mapNotNull { (i, id) -> recent[id]?.let { Triple(i, id, it.activity) } }
            .sortedWith(compareByDescending<Triple<Int, String, Long>> { it.third }.thenBy { it.first })
            .take(MAX_TITLED_FOLDERS)
            .map { it.second }
            .toSet()
        var withTitles = 0
        val out = ids.zip(lines).map { (id, line) ->
            val r = recent[id]?.takeIf { id in titled } ?: return@map line
            val examples = sample(r.titles)
            if (examples.isEmpty()) return@map line
            withTitles++
            line + " (e.g. " + examples.joinToString(", ") { "\"$it\"" } + ")"
        }
        return out to withTitles
    }

    fun group(rows: List<FolderMemberTitle>, perFolder: Int = FETCH_PER_FOLDER): Map<String, Recent> {
        val out = linkedMapOf<String, Recent>()
        for (row in rows) {
            val r = out[row.folderId]
            when {
                r == null -> out[row.folderId] = Recent(row.updatedAt, listOf(row.title))
                r.titles.size < perFolder -> out[row.folderId] = r.copy(titles = r.titles + row.title)
            }
        }
        return out
    }
}
