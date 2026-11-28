package com.openminis.app.skills.radar

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SkillCandidate(
    val id: String,
    val name: String,
    val repo: String,
    val branch: String,
    val path: String,
    val description: String = "",
    val official: Boolean = false,
) {

    val url: String get() = "https://github.com/$repo/blob/$branch/$path"

    val dirPath: String get() = path.substringBeforeLast('/', "")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("repo", repo)
        put("branch", branch)
        put("path", path)
        put("description", description)
        put("official", official)
    }

    companion object {
        fun idOf(repo: String, path: String): String {
            val dir = path.substringBeforeLast('/', "")
            return if (dir.isEmpty()) repo else "$repo/$dir"
        }

        fun of(repo: String, branch: String, path: String, official: Boolean): SkillCandidate {
            val dir = path.substringBeforeLast('/', "")
            val name = dir.substringAfterLast('/', "").ifEmpty { repo.substringAfterLast('/') }
                .replace('-', ' ').replace('_', ' ').trim()
            return SkillCandidate(
                id = idOf(repo, path),
                name = name,
                repo = repo,
                branch = branch,
                path = path,
                description = "",
                official = official,
            )
        }

        fun fromJson(o: JSONObject): SkillCandidate? {
            val id = o.optString("id", "")
            val repo = o.optString("repo", "")
            val path = o.optString("path", "")
            if (id.isEmpty() || repo.isEmpty() || path.isEmpty()) return null
            return SkillCandidate(
                id = id,
                name = o.optString("name", ""),
                repo = repo,
                branch = o.optString("branch", "main"),
                path = path,
                description = o.optString("description", ""),
                official = o.optBoolean("official", false),
            )
        }
    }
}

object SkillTree {

    const val MAX_PER_SOURCE = 1600
    private const val MAX_PATH_DEPTH = 8

    private val skipPrefixes = listOf(
        ".github/", "node_modules/", "vendor/", ".venv/", "site-packages/",
        "dist/", "build/", ".git/", "Pods/", ".gradle/", "target/",
    )

    fun parse(json: String, repo: String, branch: String, official: Boolean): List<SkillCandidate> {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val tree = root.optJSONArray("tree") ?: return emptyList()
        val out = ArrayList<SkillCandidate>()
        for (i in 0 until tree.length()) {
            if (out.size >= MAX_PER_SOURCE) break
            val node = tree.optJSONObject(i) ?: continue
            if (node.optString("type", "") != "blob") continue
            val path = node.optString("path", "")
            if (path.isEmpty()) continue
            val fileName = path.substringAfterLast('/')
            if (!fileName.equals("SKILL.md", ignoreCase = true)) continue
            if (path.count { it == '/' } > MAX_PATH_DEPTH) continue
            if (skipPrefixes.any { path.startsWith(it) }) continue
            out.add(SkillCandidate.of(repo, branch, path, official))
        }
        return out
    }
}

object SkillMatcher {

    data class Scored(val candidate: SkillCandidate, val score: Double)

    private val STOP = setOf(
        "a", "an", "the", "to", "for", "of", "in", "on", "at", "by", "with", "and", "or",
        "is", "are", "be", "do", "does", "can", "could", "would", "should", "i", "me", "my",
        "you", "your", "it", "its", "this", "that", "how", "what", "which", "when", "there",
        "skill", "skills", "skillmd", "use", "using", "need", "want", "please", "any", "some",
    )

    fun tokens(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in STOP && it.all { ch -> !isCjk(ch) } }
            .distinct()

    private fun isCjk(ch: Char): Boolean = ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF

    fun score(query: String, c: SkillCandidate): Double {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0.0
        val nameText = (c.name + " " + c.dirPath.replace('-', ' ').replace('_', ' ')).lowercase()
        val pathText = c.dirPath.lowercase()
        val descText = c.description.lowercase()
        val haystack = "$nameText\n$pathText\n$descText"
        var score = 0.0

        val cjkQuery = q.filter { isCjk(it) }
        if (cjkQuery.isNotEmpty()) {
            if (haystack.contains(cjkQuery)) score += 14.0
            else {
                for (chunk in cjkQuery.split(Regex("[^\\p{IsHan}]+")).filter { it.length >= 2 }) {
                    if (haystack.contains(chunk)) score += 7.0
                }
            }
        }

        if (haystack.contains(" $q ") || nameText.startsWith("$q ") || nameText == q) score += 6.0

        val qTokens = tokens(q)
        val nameTokens = tokens(nameText).toSet()
        val pathTokens = tokens(pathText).toSet()
        val descTokens = tokens(descText).toSet()
        var matched = 0
        for (t in qTokens) {
            var hit = 0.0
            if (nameTokens.contains(t)) hit = 9.0
            else if (t.length >= 3 && nameTokens.any { it.startsWith(t) }) hit = 5.0
            if (hit == 0.0) {
                if (pathTokens.contains(t)) hit = 4.0
                else if (t.length >= 3 && pathTokens.any { it.startsWith(t) }) hit = 2.0
            }
            if (hit == 0.0) {
                if (descTokens.contains(t)) hit = 6.0
                else if (t.length >= 3 && descTokens.any { it.startsWith(t) }) hit = 3.0
            }
            if (hit > 0.0) matched++
            score += hit
        }
        if (matched == 0 && score == 0.0) return 0.0
        if (qTokens.isNotEmpty()) score *= (0.4 + 0.6 * matched.toDouble() / qTokens.size)
        if (c.official) score += 1.0
        return score
    }

    fun rank(query: String, list: List<SkillCandidate>, limit: Int): List<Scored> {
        if (query.trim().isEmpty()) return emptyList()
        val scored = list.map { Scored(it, score(query, it)) }.filter { it.score > 0.0 }
        return scored
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.candidate.name.length }
                    .thenBy { it.candidate.id },
            )
            .take(limit.coerceIn(1, 25))
    }
}

class SkillIndexStore(private val file: File) {

    data class Snapshot(
        val fetchedAt: Long,
        val entries: List<SkillCandidate>,
        val sources: List<String>,
    )

    fun load(): Snapshot? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SkillCandidate.fromJson(it) }
            }
            val srcs = root.optJSONArray("sources") ?: JSONArray()
            val sources = (0 until srcs.length()).mapNotNull { srcs.optString(it, null) }
            Snapshot(root.optLong("fetchedAt", 0L), entries, sources)
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: Snapshot) {
        try {
            file.parentFile?.mkdirs()
            val root = JSONObject()
            root.put("fetchedAt", snapshot.fetchedAt)
            root.put("sources", JSONArray(snapshot.sources))
            val arr = JSONArray()
            snapshot.entries.forEach { arr.put(it.toJson()) }
            root.put("entries", arr)
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun patchDescriptions(updates: List<SkillCandidate>) {
        if (updates.isEmpty()) return
        val current = load() ?: return
        val byId = current.entries.associateBy { it.id }.toMutableMap()
        var changed = false
        for (u in updates) {
            val old = byId[u.id] ?: continue
            if (u.description.isNotEmpty() && u.description != old.description) {
                byId[u.id] = old.copy(name = u.name.ifEmpty { old.name }, description = u.description)
                changed = true
            }
        }
        if (changed) save(current.copy(entries = byId.values.toList()))
    }
}
