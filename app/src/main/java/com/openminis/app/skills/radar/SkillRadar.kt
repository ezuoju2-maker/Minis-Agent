package com.openminis.app.skills.radar

import android.content.Context
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.network.NetworkHttp
import com.openminis.app.network.NetworkUrlRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SkillRadar(
    private val context: Context,
    private val repository: SkillRepository,
) {

    data class Source(val repo: String, val branch: String, val official: Boolean)

    private val store = SkillIndexStore(File(context.filesDir, "skills_radar/index.json"))

    private fun prefs() = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(url: String, timeoutMs: Int = 20_000): String? = try {
        NetworkUrlRequest.open(
            NetworkHttp.DEFAULT_TAG,
            url,
            headers = mapOf("User-Agent" to UA, "Accept" to "application/vnd.github+json"),
            connectTimeoutMs = 8_000,
            readTimeoutMs = timeoutMs,
        ).use { r -> if (r.code in 200..299) r.text() else null }
    } catch (_: Exception) {
        null
    }

    fun sources(): List<Source> {
        val manifest = prefs().getString(KEY_MANIFEST_SOURCES, null)
        val fromManifest = ArrayList<Source>()
        if (manifest != null) {
            try {
                val arr = JSONArray(manifest)
                for (i in 0 until arr.length()) {
                    val s = sourceFromJson(arr.optJSONObject(i)) ?: continue
                    if (fromManifest.none { it.repo == s.repo }) fromManifest.add(s)
                }
            } catch (_: Exception) {
            }
        }
        val stored = prefs().getString(KEY_SOURCES, null)
        val extra = ArrayList<Source>()
        if (stored != null) {
            try {
                val arr = JSONArray(stored)
                for (i in 0 until arr.length()) {
                    val s = sourceFromJson(arr.optJSONObject(i)) ?: continue
                    if (extra.none { it.repo == s.repo }) extra.add(s)
                }
            } catch (_: Exception) {
            }
        }
        val out = ArrayList<Source>()
        for (s in BUILTIN) if (out.none { it.repo == s.repo }) out.add(s)
        for (s in fromManifest) if (out.none { it.repo == s.repo }) out.add(s)
        for (s in extra) if (out.none { it.repo == s.repo }) out.add(s)
        return out
    }

    fun addSource(raw: String): String {
        val parsed = parseRepoSpec(raw) ?: return "Rejected: give a GitHub repo as owner/name (optional :branch)."
        if (BUILTIN.any { it.repo.equals(parsed.repo, ignoreCase = true) }) {
            return "${parsed.repo} is already a built-in source."
        }
        val list = sources().filter { BUILTIN.none { b -> b.repo == it.repo } }
        if (list.any { it.repo.equals(parsed.repo, true) }) return "${parsed.repo} is already a source."
        if (list.size >= 8) return "Rejected: at most 8 custom sources."
        val next = list + parsed
        prefs().edit().putString(KEY_SOURCES, toJson(next).toString()).apply()
        return "Added ${parsed.repo} (${parsed.branch}). Run find_skills with action=refresh to index it."
    }

    fun removeSource(raw: String): String {
        val repo = raw.trim().substringAfter("github.com/", raw.trim()).trim('/').lowercase()
        if (BUILTIN.any { it.repo.lowercase() == repo }) return "$repo is built in and cannot be removed."
        val list = sources().filter { BUILTIN.none { b -> b.repo == it.repo } }
        val kept = list.filter { !it.repo.equals(repo, true) }
        if (kept.size == list.size) return "$repo is not a custom source."
        prefs().edit().putString(KEY_SOURCES, toJson(kept).toString()).apply()
        return "Removed $repo."
    }

    suspend fun refresh(force: Boolean): String = withContext(Dispatchers.IO) { refreshInner(force) }

    private suspend fun refreshInner(force: Boolean): String {
        val manifestAt = prefs().getLong(KEY_MANIFEST_AT, 0L)
        if (force || System.currentTimeMillis() - manifestAt > MANIFEST_TTL_MS) {
            refreshManifest()
        }
        val srcs = sources()
        val entries = ArrayList<SkillCandidate>()
        val notes = ArrayList<String>()
        for (s in srcs) {
            if (entries.size >= MAX_ENTRIES) break
            val json = get("https://api.github.com/repos/${s.repo}/git/trees/${s.branch}?recursive=1", 25_000)
            if (json == null) {
                notes.add("${s.repo}: unavailable (network, rate limit or missing branch)")
                continue
            }
            val parsed = SkillTree.parse(json, s.repo, s.branch, s.official)
            if (parsed.isEmpty()) {
                notes.add("${s.repo}: no SKILL.md found")
                continue
            }
            entries.addAll(parsed.take(MAX_ENTRIES - entries.size))
        }
        if (entries.isEmpty()) {
            return "Refresh failed: no source produced an index. ${notes.joinToString("; ")}"
        }
        store.save(SkillIndexStore.Snapshot(System.currentTimeMillis(), entries, srcs.map { it.repo }))
        val covered = notes.joinToString("; ")
        val ok = srcs.size - notes.size
        return "Indexed ${entries.size} skills from $ok/${srcs.size} sources" +
            (if (covered.isEmpty()) "" else " — $covered")
    }

    private fun refreshManifest() {
        val url = prefs().getString(KEY_MANIFEST, null) ?: DEFAULT_MANIFEST
        val body = get(url, 12_000) ?: return
        val arr = try {
            JSONObject(body).optJSONArray("sources")
        } catch (_: Exception) {
            null
        } ?: return
        val kept = ArrayList<Source>()
        for (i in 0 until arr.length()) {
            val s = sourceFromJson(arr.optJSONObject(i)) ?: continue
            if (kept.none { it.repo == s.repo }) kept.add(s)
        }
        if (kept.isEmpty()) return
        prefs().edit()
            .putString(KEY_MANIFEST_SOURCES, toJson(kept).toString())
            .putLong(KEY_MANIFEST_AT, System.currentTimeMillis())
            .apply()
    }

    private fun ensureFresh(): SkillIndexStore.Snapshot? {
        val snap = store.load()
        if (snap != null && snap.entries.isNotEmpty() && System.currentTimeMillis() - snap.fetchedAt <= TTL_MS) {
            return snap
        }
        return snap
    }

    fun indexAge(): String {
        val snap = store.load() ?: return "no index yet"
        val minutes = (System.currentTimeMillis() - snap.fetchedAt) / 60_000
        val age = when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            else -> "${minutes / 1440}d ago"
        }
        return "${snap.entries.size} skills, $age"
    }

    suspend fun search(query: String, limit: Int, autoRefresh: Boolean): Pair<List<SkillCandidate>, String> {
        var snap = ensureFresh()
        var note = ""
        val stale = snap == null || System.currentTimeMillis() - snap.fetchedAt > TTL_MS
        if (stale && autoRefresh) {
            val report = refresh(false)
            note = report
            snap = store.load()
        }
        if (snap == null || snap.entries.isEmpty()) {
            return emptyList<SkillCandidate>() to (
                "No index available. " + if (note.isEmpty()) "Run action=refresh to build one." else note
                )
        }
        val ranked = SkillMatcher.rank(query, snap.entries, (limit * 2).coerceAtLeast(6))
        val top = ranked.take(limit).map { it.candidate }
        val enriched = withContext(Dispatchers.IO) {
            top.map { c -> if (c.description.isNotEmpty()) c else enrich(c) ?: c }
        }
        val updates = enriched.filter { it.description.isNotEmpty() && top.any { t -> t.id == it.id && t.description.isEmpty() } }
        if (updates.isNotEmpty()) store.patchDescriptions(updates)
        val final = SkillMatcher.rank(query, enriched, limit).map { it.candidate }
        val installed = repository.skills.value
        val installedCount = final.count { c -> installed.any { it.sourceURL == c.url } }
        val suffix = buildString {
            if (note.isNotEmpty()) append(" [").append(note).append(']')
            if (installedCount > 0) append(" (").append(installedCount).append(" already installed)")
        }
        return final to suffix
    }

    private suspend fun enrich(c: SkillCandidate): SkillCandidate? {
        if (c.path.isEmpty()) return null
        val raw = "https://raw.githubusercontent.com/${c.repo}/${c.branch}/${c.path}"
        val content = get(raw, 10_000) ?: return null
        val parsed = repository.parseSkillMdPublic(content) ?: return null
        val desc = parsed.description.replace('\n', ' ').trim().take(300)
        return c.copy(name = parsed.name.ifBlank { c.name }, description = desc)
    }

    suspend fun install(idOrUrl: String): String {
        val raw = idOrUrl.trim()
        if (raw.isEmpty()) return "Error: give an `id` from a search result, or a GitHub url."
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            val snap = store.load()
            val hit = snap?.entries?.firstOrNull { it.id.equals(raw, true) }
                ?: snap?.entries?.firstOrNull { it.id.equals(raw, true) || it.name.equals(raw, true) }
                ?: return "Error: unknown skill \"$raw\". Search first and pass the exact id."
            hit.url
        }
        val skill = repository.importFromGitHub(url)
            ?: return "Install failed: $url is not a valid SKILL.md source (must be github.com or raw.githubusercontent.com)."
        repository.setEnabled(skill.id, true)
        return "Installed \"${skill.name}\" (${skill.version}) from $url. Read it with skill_load when you need its steps."
    }

    private fun parseRepoSpec(raw: String): Source? {
        var s = raw.trim().removePrefix("https://").removePrefix("http://")
            .removePrefix("github.com/").trim('/')
        if (s.count { it == '/' } < 1) return null
        val branch = s.substringAfter(':', "main").ifBlank { "main" }
        s = s.substringBefore(':')
        val parts = s.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val repo = parts[0] + "/" + parts[1]
        if (!REPO_RE.matches(repo)) return null
        return Source(repo, branch, official = false)
    }

    private fun sourceFromJson(o: JSONObject?): Source? {
        if (o == null) return null
        val repo = o.optString("repo", "")
        if (!REPO_RE.matches(repo)) return null
        return Source(repo, o.optString("branch", "main").ifBlank { "main" }, o.optBoolean("official", false))
    }

    private fun toJson(list: List<Source>): JSONArray = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().put("repo", s.repo).put("branch", s.branch).put("official", s.official))
        }
    }

    companion object {
        const val PREFS = "agent_settings"
        const val KEY_SOURCES = "skills.radar.sources"
        const val KEY_MANIFEST = "skills.radar.manifest"
        private const val KEY_MANIFEST_SOURCES = "skills.radar.manifest.sources"
        private const val KEY_MANIFEST_AT = "skills.radar.manifest.at"
        const val MANIFEST_TTL_MS = 7L * 24 * 3600 * 1000
        const val DEFAULT_MANIFEST = "https://raw.githubusercontent.com/ezuoju2-maker/Minis-Agent/main/skills-sources.json"
        const val TTL_MS = 24L * 3600 * 1000
        const val MAX_ENTRIES = 3000
        private const val UA = "Minis-Agent/2.0 skill-radar"
        private val REPO_RE = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

        val BUILTIN: List<Source> = listOf(
            Source("anthropics/skills", "main", true),
            Source("obra/superpowers", "main", false),
            Source("affaan-m/ECC", "main", false),
            Source("ComposioHQ/awesome-claude-skills", "master", false),
        )
    }
}
