package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object FindSkillsTool {

    const val NAME = "find_skills"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the live public skill ecosystem and install the SKILL.md that fits the job at hand. " +
            "Sources are crawled from GitHub, not a fixed list: Anthropic Skills, Superpowers, ECC, Composio, " +
            "WorkBuddy and OpenClaw community packs, plus Chinese and niche repos the radar discovers on its own " +
            "— roughly thousands of SKILL.md files, refreshed every 24h, with new repos auto-discovered weekly.\n\n" +
            "WHEN TO USE: at the START of any task that needs a specialized capability you do not already have " +
            "(PDF forms, slides, posters, e-commerce hero images, short-drama scripts, data cleaning, code review, " +
            "deploy, SEO, security recon…). The user should never have to ask whether a skill exists — you look, " +
            "install and use it yourself, and only then fall back to improvising. Also use it when the user asks " +
            "\"can you do X\" / \"is there a skill for X\". Do NOT use it for ordinary chat, or for work plain " +
            "shell/file tools already handle.\n\nFLOW: action=search returns ranked candidates with an `id`; if " +
            "one clearly fits, action=install that id, then skill_load it and follow its steps. Never install a " +
            "loosely related skill \"just in case\". action=refresh re-crawls and re-discovers sources; " +
            "action=sources lists them; add_source/remove_source manage extra GitHub repos.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of this call, shown to the user. Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "\"search\" (default): rank skills for `query`. \"install\": fetch and enable the skill named by " +
                    "`id` (or `url`). \"refresh\": re-crawl sources now. \"sources\": list configured sources and " +
                    "index age. \"add_source\": add a GitHub repo (`source`, owner/name or owner/name:branch). " +
                    "\"remove_source\": drop a custom repo.",
                enumValues = listOf("search", "install", "refresh", "sources", "add_source", "remove_source"),
            ),
            "query" to AgentToolParam(
                "string",
                "search only, required. What the user wants to achieve, in a few keywords (\"pdf form filling\", " +
                    "\"brand guidelines\", \"changelog\"). English tends to match skill names better, but the " +
                    "user's own words are fine.",
            ),
            "limit" to AgentToolParam(
                "integer",
                "search only. How many candidates to return (default 6, max 12).",
            ),
            "id" to AgentToolParam(
                "string",
                "install only. The `id` printed by a previous search result.",
            ),
            "url" to AgentToolParam(
                "string",
                "install only, alternative to id. A github.com or raw.githubusercontent.com url pointing at a SKILL.md.",
            ),
            "source" to AgentToolParam(
                "string",
                "add_source / remove_source only. GitHub repo as owner/name (optionally owner/name:branch).",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "query", "limit", "id", "url", "source"),
    )
}
