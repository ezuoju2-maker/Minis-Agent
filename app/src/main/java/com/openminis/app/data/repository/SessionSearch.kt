package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object SessionSearch {


    const val MAX_TERMS = 10

    fun searchTerms(query: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val piece = StringBuilder()
        fun flush(): Boolean {
            if (piece.isEmpty()) return false
            val term = piece.toString()
            piece.setLength(0)
            if (seen.add(term.lowercase())) out.add(term)
            return out.size == MAX_TERMS
        }
        for (c in query) {
            if (!c.isWhitespace()) piece.append(c) else if (flush()) return out
        }
        flush()
        return out
    }

    fun likePattern(term: String): String {
        val escaped = StringBuilder(term.length + 2)
        for (c in term) {
            if (c == '\\' || c == '%' || c == '_') escaped.append('\\')
            escaped.append(c)
        }
        return "%$escaped%"
    }

    fun searchArgs(terms: List<String>): Array<Any> =
        (terms.map { likePattern(it) } + terms).toTypedArray()

    fun anyLike(column: String, termCount: Int): String =
        (1..maxOf(1, termCount)).joinToString(" OR ", "(", ")") { "$column LIKE ?$it ESCAPE '\\'" }

    fun visibleHitClause(termCount: Int): String = """
        ${anyLike("m.parts_json", termCount)} AND EXISTS (
            SELECT 1 FROM json_each(m.parts_json) p
            WHERE (json_extract(p.value, '$.type') = 'text' AND ${anyLike("json_extract(p.value, '$.value')", termCount)})
               OR (json_extract(p.value, '$.type') = 'toolUse' AND ${anyLike("json_extract(p.value, '$.value.input')", termCount)})
               OR (json_extract(p.value, '$.type') = 'toolResult' AND ${anyLike("json_extract(p.value, '$.value.output')", termCount)}))
    """

    const val MAX_HIT_JSON_CHARS = 200_000

    const val HIT_WINDOW_CHARS = 4_000

    const val WINDOW_MARK = "#minis-search-window#"

    fun cappedParts(col: String, termCount: Int): String {
        val n = maxOf(1, termCount)
        val firstHit = (n + 1..2 * n).joinToString(", ", "COALESCE(", ", 1)") { "NULLIF(instr(lower($col), lower(?$it)), 0)" }
        return """
        CASE WHEN length($col) <= $MAX_HIT_JSON_CHARS THEN $col
             ELSE '$WINDOW_MARK' || substr($col, max(1, $firstHit - ${HIT_WINDOW_CHARS / 4}), $HIT_WINDOW_CHARS)
        END"""
    }

    fun visibleSearchSql(termCount: Int): String = """
        WITH newest AS (
            SELECT m.session_id, m.parts_json, MAX(m.sort_order)
            FROM messages m
            WHERE ${visibleHitClause(termCount)}
            GROUP BY m.session_id)
        SELECT s.*, ${cappedParts("n.parts_json", termCount)} AS hit_parts_json
        FROM sessions s
        LEFT JOIN newest n ON n.session_id = s.id
        WHERE ${anyLike("s.title", termCount)} OR n.session_id IS NOT NULL
        ORDER BY s.updated_at DESC
    """

    fun legacySearchSql(termCount: Int): String = """
        SELECT s.*,
               (SELECT ${cappedParts("m2.parts_json", termCount)}
                FROM messages m2
                WHERE m2.session_id = s.id AND ${anyLike("m2.parts_json", termCount)}
                ORDER BY m2.sort_order DESC LIMIT 1) AS hit_parts_json
        FROM sessions s
        WHERE ${anyLike("s.title", termCount)}
           OR EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND ${anyLike("m.parts_json", termCount)})
        ORDER BY s.updated_at DESC
    """

    fun olderMatchesSql(visibleOnly: Boolean, termCount: Int): String {
        val n = maxOf(1, termCount)
        return """
        SELECT ${cappedParts("m.parts_json", n)} AS parts_json FROM messages m
        WHERE m.session_id = ?${2 * n + 1} AND ${if (visibleOnly) visibleHitClause(n) else anyLike("m.parts_json", n)}
        ORDER BY m.sort_order DESC LIMIT $OLDER_LOOKBACK
    """
    }

    const val OLDER_LOOKBACK = 20

    const val OLDER_LOOKBACK_SESSIONS = 40

    fun searchHitLine(text: String, query: String, maxLength: Int = 90, lead: Int = 18): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val hit = text.indexOf(q, ignoreCase = true)
        if (hit < 0) return null
        var lineStart = hit
        while (lineStart > 0 && !isNewline(text[lineStart - 1])) lineStart--
        var lineEnd = hit + q.length
        while (lineEnd < text.length && !isNewline(text[lineEnd])) lineEnd++
        val line = text.substring(lineStart, lineEnd)

        val cpCount = line.codePointCount(0, line.length)
        val hitStart = line.codePointCount(0, hit - lineStart)
        val hitLength = line.codePointCount(hit - lineStart, hit - lineStart + q.length)
        var from = 0
        var to = cpCount
        if (cpCount > maxLength) {
            from = maxOf(0, hitStart - lead)
            to = minOf(cpCount, maxOf(from + maxLength, hitStart + hitLength))
        }
        var window = line.substring(line.offsetByCodePoints(0, from), line.offsetByCodePoints(0, to))
        val body = window.trim { it == ' ' || it == '\t' }
        if (body.contains(q, ignoreCase = true)) window = body
        return (if (from > 0) "…" else "") + window + (if (to < cpCount) "…" else "")
    }

    private fun isNewline(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' ' || c == '\u0085'

    fun toolInputStrings(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed[0] !in "{[\"") return emptyList()
        val root = try {
            val tokener = JSONTokener(trimmed)
            tokener.nextValue().also { if (tokener.more()) return emptyList() }
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is String -> out.add(v)
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> v.keys().asSequence().sorted().forEach { walk(v.opt(it)) }
            }
        }
        walk(root)
        return out
    }

    fun firstHit(text: String, terms: List<String>): String? =
        terms.firstOrNull { it.isNotEmpty() && text.contains(it, ignoreCase = true) }

    fun hitRanges(text: String, terms: List<String>): List<IntRange> {
        val found = ArrayList<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var from = 0
            while (from < text.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                found.add(at until at + term.length)
                from = at + term.length
            }
        }
        found.sortBy { it.first }
        val merged = ArrayList<IntRange>(found.size)
        for (r in found) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        return merged
    }

    fun searchSnippet(partsJson: String, query: String): String? =
        query.trim().takeIf { it.isNotEmpty() }?.let { searchSnippet(partsJson, listOf(it)) }

    fun searchSnippet(partsJson: String, terms: List<String>): String? {
        fun hitLine(text: String): Pair<String, String>? {
            val term = firstHit(text, terms) ?: return null
            return searchHitLine(text, term)?.let { it to term }
        }
        if (partsJson.startsWith(WINDOW_MARK)) return hitLine(partsJson.removePrefix(WINDOW_MARK))?.first
        val parts = try {
            val arr = JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) {
            return null
        }
        for (p in parts) {
            if (p.optString("type") != "text") continue
            val t = stripAttachmentMarkers(stripAttachedFiles(ChatRepository.stripSystemReminders(p.optString("value"))))
            val shown = com.openminis.app.ui.markdown.InlineHtmlTags.stripForPreview(t)
            (hitLine(shown) ?: hitLine(t))?.let { (line, term) -> return readable(line, term) }
        }
        for (p in parts) {
            if (p.optString("type") != "toolUse") continue
            val v = p.optJSONObject("value") ?: continue
            val input = v.optString("input")
            for (text in toolInputStrings(input) + input) {
                hitLine(text)?.let { return "🔧 ${v.optString("name")}: ${it.first}" }
            }
        }
        for (p in parts) {
            if (p.optString("type") != "toolResult") continue
            val output = p.optJSONObject("value")?.optString("output").orEmpty()
            hitLine(output)?.let { return "🔧 ${it.first}" }
        }
        return null
    }

    internal fun readable(line: String, query: String): String {
        val plain = stripInlineMarkdown(line).trim { it == ' ' || it == '\t' }
        return if (plain.contains(query.trim(), ignoreCase = true)) plain else line
    }

    private val MD_LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val MD_EMPHASIS = Regex("""(\*\*|__|~~|`)""")
    private val MD_LINE_PREFIX = Regex("""^\s*(#{1,6}\s+|>\s?|[-*+]\s+|\d+[.)]\s+)""")

    internal fun stripInlineMarkdown(line: String): String {
        var s = MD_LINE_PREFIX.replace(line, "")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = MD_EMPHASIS.replace(s, "")
        return s
    }

    private fun stripAttachedFiles(text: String): String {
        val start = text.indexOf("<user-attached-files>")
        if (start < 0) return text
        val endTag = "</user-attached-files>"
        val end = text.indexOf(endTag, start)
        return if (end >= 0) text.substring(0, start) + text.substring(end + endTag.length) else text.substring(0, start)
    }

    private val ATTACHMENT_MARKERS = listOf(
        Regex("""\[attached [A-Za-z]+:[^\]]*]"""),
        Regex("""\[image omitted to save context[^\]]*]"""),
    )

    private fun stripAttachmentMarkers(text: String): String {
        if (!text.contains('[')) return text
        var out = text
        for (re in ATTACHMENT_MARKERS) out = re.replace(out, "")
        return out
    }
}
