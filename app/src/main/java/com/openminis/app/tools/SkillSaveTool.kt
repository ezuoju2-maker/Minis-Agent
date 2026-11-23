package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONObject

/**
 * [self-improvement] Turns a *proven, reusable* procedure into a durable skill
 * so the agent gets better over time (Voyager-style skill library / Anthropic
 * Agent Skills "progressive disclosure"). The agent calls this after it has
 * actually solved a non-trivial, repeatable task, writing a compact SKILL.md
 * that a future session can reload with `skill_load` in one cheap step instead
 * of rediscovering the whole procedure.
 *
 * Deliberately gated by the tool description (only reusable methods, not
 * one-off facts) so we don't flood the skill catalog with noise or burn tokens
 * on every turn. Skills are tagged [SkillRepository.ImportSource.SESSION] to
 * distinguish agent-authored skills from imported ones.
 */
object SkillSaveTool {

    const val NAME = "skill_save"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = (
            "Save a reusable procedure you have just successfully carried out as a skill, so future sessions " +
            "can reuse it with skill_load instead of rediscovering it (you get better over time). " +
            "Use ONLY when the method is non-trivial, generalizable and likely to recur — e.g. a multi-step " +
            "workflow, a reliable recipe for a tool/CLI, a project-specific setup you worked out. " +
            "Do NOT use for one-off facts, conversation summaries, or things already covered by an existing " +
            "skill. Write a concise, self-contained SKILL.md body: goal, when to use, prerequisites, and " +
            "numbered steps with the exact commands/parameters that worked. Keep it short and reusable. " +
            "If a skill with the same name already exists, this returns an error — pick a distinct, descriptive name."
        ),
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this call does, shown to the user. Use the same language as the user."
            ),
            "name" to AgentToolParam(
                "string",
                "A short, distinctive skill name (3-40 chars), e.g. 'Batch resize images with ImageMagick' or 'Set up Python venv in workspace'."
            ),
            "description" to AgentToolParam(
                "string",
                "One sentence: what this skill does and exactly when to use it. This is what future sessions scan to decide whether to load it, so make the trigger conditions concrete."
            ),
            "body" to AgentToolParam(
                "string",
                "The SKILL.md instructions: goal, when to use, prerequisites, and numbered steps with the exact commands/parameters that worked. Markdown. Self-contained and reusable."
            ),
        ),
        required = listOf("tool_title", "name", "description", "body"),
        propertyOrdering = listOf("tool_title", "name", "description", "body"),
    )

    fun execute(
        context: Context,
        repo: SkillRepository,
        argsJson: String,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid arguments", false)
        }
        val toolTitle = args.optString("tool_title", "Save skill")
        val name = args.optString("name", "").trim()
        val description = args.optString("description", "").trim()
        val body = args.optString("body", "").trim()

        when {
            name.length < 3 || name.length > 80 ->
                return ToolExecutionResult("Error: 'name' must be 3-80 characters", false, toolTitle = toolTitle)
            description.length < 8 ->
                return ToolExecutionResult("Error: 'description' must be a meaningful sentence (8+ chars)", false, toolTitle = toolTitle)
            body.length < 40 ->
                return ToolExecutionResult("Error: 'body' is too short to be reusable (40+ chars). Include concrete steps.", false, toolTitle = toolTitle)
        }

        if (repo.skills.value.any { it.name.equals(name, ignoreCase = true) }) {
            return ToolExecutionResult(
                "A skill named '$name' already exists. Pick a distinct name, or update the existing one via Settings → Skills.",
                false,
                toolTitle = toolTitle,
            )
        }

        val added = try {
            repo.add(
                name = name,
                description = description,
                body = body,
                version = "1.0.0",
                source = SkillRepository.ImportSource.SESSION,
            )
        } catch (e: Exception) {
            return ToolExecutionResult("Error saving skill: ${e.message}", false, toolTitle = toolTitle)
        }

        if (added == null) {
            return ToolExecutionResult(
                "Could not save skill '$name' (invalid name or duplicate id). Try a different name.",
                false,
                toolTitle = toolTitle,
            )
        }

        return ToolExecutionResult(
            "Skill saved as '${added.name}' (id=${added.id}). It is now available in <available_skills> on the next turn — future sessions can load it with skill_load(\"${added.name}\").",
            true,
            toolTitle = toolTitle,
        )
    }
}
