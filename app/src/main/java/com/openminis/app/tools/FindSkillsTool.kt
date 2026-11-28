package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object FindSkillsTool {

    const val NAME = "find_skills"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the live public skill ecosystem (Anthropic Skills, Superpowers, ECC, Composio's " +
            "awesome-claude-skills and any extra sources the user added) for a SKILL.md that already solves what " +
            "the user is asking, and install the one you pick.\n\nWHEN TO USE: the user says \"can you do X\", " +
            "\"how do I do X\", \"is there a skill for X\", or asks for a specialized capability you do not have " +
            "built in (PDF form filling, slide design, brand guidelines, data cleaning, code review checklists…). " +
            "Call this BEFORE telling the user something is unavailable — the ecosystem is large and the index " +
            "refreshes itself every 24h. Do NOT call it for ordinary chat, for work you can already do with " +
            "shell/file tools, or when the user only wants an explanation.\n\nFLOW: action=search returns ranked " +
            "candidates with an `id`. If one clearly fits, call action=install with that id, then skill_load it to " +
            "read the steps. If nothing fits, say so and fall back to your own tools — never install a loosely " +
            "related skill \"just in case\". action=refresh re-crawls sources; action=sources lists them; " +
            "add_source/remove_source manage extra GitHub repos.",
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
