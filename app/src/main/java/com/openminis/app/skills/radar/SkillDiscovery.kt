package com.openminis.app.skills.radar

import org.json.JSONObject

object SkillDiscovery {

    data class Found(
        val repo: String,
        val branch: String,
        val stars: Int,
        val description: String,
    )

    val QUERIES: List<String> = listOf(
        "SKILL.md+skill",
        "workbuddy+skill",
        "openclaw+skill",
        "claude+skills",
    )

    private val NAME_RE = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    private val skillSignals = listOf("skill", "agent", "mcp", "工作流", "技能")

    fun parse(
        json: String,
        known: Set<String>,
        minStars: Int = 100,
        limit: Int = 8,
    ): List<Found> {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Found>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optBoolean("archived", false) || o.optBoolean("fork", false) || o.optBoolean("disabled", false)) continue
            val repo = o.optString("full_name", "")
            if (!NAME_RE.matches(repo)) continue
            if (known.any { it.equals(repo, true) }) continue
            if (repo.substringAfterLast('/').contains("minis", true)) continue
            val stars = o.optInt("stargazers_count", 0)
            if (stars < minStars) continue
            val desc = o.optString("description", "")
            if (!hasSignal(repo, desc, o)) continue
            val branch = o.optString("default_branch", "main").ifBlank { "main" }
            if (out.none { it.repo == repo }) {
                out.add(Found(repo, branch, stars, desc.take(200)))
            }
        }
        return out.sortedByDescending { it.stars }.take(limit)
    }

    private fun hasSignal(repo: String, desc: String, o: JSONObject): Boolean {
        val topics = o.optJSONArray("topics")?.let { arr ->
            (0 until arr.length()).joinToString(" ") { arr.optString(it, "") }
        } ?: ""
        val hay = (repo + " " + desc + " " + topics).lowercase()
        return skillSignals.any { hay.contains(it) }
    }
}
