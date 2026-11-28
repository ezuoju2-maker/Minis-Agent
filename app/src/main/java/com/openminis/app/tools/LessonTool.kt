package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object LessonTool {

    const val NAME = "lesson_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "The self-improving notebook — four journals on disk: errors.jsonl (failed commands and " +
            "bugs), corrections.jsonl (your corrections and preferences), best_practices.jsonl (better ways you " +
            "found), knowledge_gaps.jsonl (outdated APIs). This is how you stop repeating a mistake weeks later " +
            "in a fresh session.\n\nWHEN TO WRITE: the user corrects you (\"不对\", \"应该是…\", \"以后都…\"), states " +
            "a preference (\"我不吃辣\", \"报告要 Markdown\"), you hit a failure worth remembering, or you discover " +
            "a clearly better approach. Write ONE imperative line, ≤120 characters, valid with no other context. " +
            "Skip one-off task details, passwords, and secrets.\n\nHOW IT WORKS: journals are recalled into your " +
            "prompt automatically when a later task resembles them and apply silently. A pattern repeated 3 times " +
            "is flagged for promotion: ask the user once whether to keep it permanently, then call " +
            "action=promote — that also writes it into long-term memory. `action=list` / `stats` / `forget` manage " +
            "the notebook; never recite it to the user.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of this call, shown to the user. Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "\"write\" (default): save or reinforce `text`. \"list\": show all entries. \"forget\": drop an " +
                    "entry by `id` or close text match. \"promote\": keep entry `id` permanently (writes it into " +
                    "long-term memory too). \"stats\": counts per journal.",
                enumValues = listOf("write", "list", "forget", "promote", "stats"),
            ),
            "kind" to AgentToolParam(
                "string",
                "write only. Which journal: correction (user correction/preference), error (failure worth " +
                    "remembering), best_practice (a better way you found), knowledge_gap (outdated API/approach). " +
                    "Default correction.",
                enumValues = listOf("correction", "error", "best_practice", "knowledge_gap"),
            ),
            "category" to AgentToolParam(
                "string",
                "write only. preference (about the user), workflow (process/rules), technical (code/API/paths), " +
                    "identity (who you are), persona (style/tone). Inferred when omitted.",
                enumValues = listOf("preference", "workflow", "technical", "identity", "persona"),
            ),
            "text" to AgentToolParam(
                "string",
                "write/forget: one imperative, reusable rule (≤120 chars) or the text to forget.",
            ),
            "tags" to AgentToolParam(
                "array",
                "write only, optional. Short lowercase topic tags (e.g. [\"files\", \"reports\"]).",
            ),
            "id" to AgentToolParam(
                "string",
                "forget/promote only. The id printed by `list` or by a promotion prompt.",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "kind", "category", "text", "tags", "id"),
    )
}
