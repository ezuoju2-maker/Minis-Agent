package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONObject
import java.io.File

/**
 * [OPT-skill-load] Loads a skill's full SKILL.md instructions by NAME instead
 * of making the model guess a filesystem path. The system prompt lists each
 * skill's name + path, and previously the model had to reconstruct
 * `/var/minis/skills/<id>/SKILL.md` (it often hallucinated the id, since the
 * prompt shows the display name, not the storage id). This tool resolves by
 * display name, id, or fuzzy name match and returns the real file contents,
 * recording usage like a normal file_read of SKILL.md does.
 */
object SkillLoadTool {

    const val NAME = "skill_load"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Load the full instructions of a skill by its name. Use this when a task matches an available skill listed in the system prompt — call skill_load with the skill's name to get its complete SKILL.md content before following it. This is more reliable than file_read because you do not need to know the skill's storage path or id.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this call does, shown to the user. Use the same language as the user."),
            "name" to AgentToolParam("string", "The skill's display name (as shown in <available_skills>), or its id, or a distinctive fragment of the name. Matching is case-insensitive."),
        ),
        required = listOf("tool_title", "name"),
        propertyOrdering = listOf("tool_title", "name"),
    )

    fun execute(
        context: Context,
        repo: SkillRepository,
        argsJson: String,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid arguments", false)
        }
        val toolTitle = args.optString("tool_title", "Load skill")
        val query = args.optString("name", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'name' is required", false, toolTitle = toolTitle)
        }

        val all = repo.skills.value
        val q = query.lowercase()
        // Exact name → exact id → name starts-with → contains → id contains.
        val skill = all.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: all.firstOrNull { it.id.equals(query, ignoreCase = true) }
            ?: all.firstOrNull { it.name.lowercase().startsWith(q) }
            ?: all.firstOrNull { it.name.lowercase().contains(q) }
            ?: all.firstOrNull { it.id.lowercase().contains(q) }

        if (skill == null) {
            val names = all.joinToString(", ") { it.name }.ifEmpty { "(no skills installed)" }
            return ToolExecutionResult(
                "Skill '$query' not found. Available skills: $names",
                false,
                toolTitle = toolTitle,
            )
        }

        // Read the on-disk SKILL.md (the full source, including any files the
        // model may need), falling back to the DB-parsed body.
        val mdFile = File(context.filesDir, "minis-global/skills/${skill.id}/SKILL.md")
        val content = try {
            if (mdFile.exists()) mdFile.readText() else skill.body
        } catch (_: Exception) { skill.body }

        repo.recordSkillUse(skill.id)
        val header = "Loaded skill '${skill.name}' (${skill.id}):\n\n"
        return ToolExecutionResult(header + content, content.isNotBlank(), toolTitle = toolTitle)
    }
}
