package com.openminis.app.agent.fleet

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits

object AgentForge {

    data class Request(
        val name: String = "",
        val whenToUse: String = "",
        val goal: String = "",
        val steps: List<String> = emptyList(),
        val constraints: List<String> = emptyList(),
        val outputFormat: String = "",
        val instructions: String = "",
        val modelGroupId: String? = null,
        val thinking: String? = null,
    ) {
        val normalizedName: String get() = name.trim().replace(Regex("\\s+"), " ")
        val normalizedWhenToUse: String get() = whenToUse.trim().replace(Regex("\\s+"), " ")
    }

    fun validate(req: Request, existing: List<SubAgentDefinition>): String? {
        val name = req.normalizedName
        if (name.isEmpty()) return "Error: `name` is required — it is what the roster shows and what you pass to subagent_task."
        if (name.length > SubAgentLimits.NAME_MAX_LENGTH) {
            return "Error: name is ${name.length} characters; the limit is ${SubAgentLimits.NAME_MAX_LENGTH}."
        }
        if (existing.any { !it.isBuiltIn && it.name.trim().equals(name, ignoreCase = true) }) {
            return "Error: a sub agent named \"$name\" already exists. Pick another name or update that one."
        }
        if (existing.count { !it.isBuiltIn } >= SubAgentLimits.MAX_COUNT - 1) {
            return "Error: the roster already holds the maximum of ${SubAgentLimits.MAX_COUNT} sub agents. Remove one first."
        }
        val description = descriptionOf(req)
        if (description.isEmpty()) {
            return "Error: describe when to use this agent (`when_to_use` or `goal`) — that sentence is how the main agent decides to delegate to it."
        }
        if (description.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH) {
            return "Error: the when-to-use description is ${description.length} characters; the limit is " +
                "${SubAgentLimits.DESCRIPTION_MAX_LENGTH}. Shorten it."
        }
        val body = bodyOf(req)
        if (body.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) {
            return "Error: the generated instructions are ${body.length} characters; the limit is " +
                "${SubAgentLimits.INSTRUCTIONS_MAX_LENGTH}."
        }
        return null
    }

    fun descriptionOf(req: Request): String {
        val explicit = req.normalizedWhenToUse
        val source = explicit.ifEmpty { req.goal.trim().replace(Regex("\\s+"), " ") }
        return source.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH)
    }

    fun bodyOf(req: Request): String {
        if (req.instructions.isNotBlank()) return req.instructions.trim().take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)
        val sb = StringBuilder()
        sb.append("You are ").append(req.normalizedName.ifEmpty { "a specialist sub agent" }).append('.')
        val goal = req.goal.trim()
        if (goal.isNotEmpty()) sb.append("\n\nGoal: ").append(goal)
        val steps = req.steps.map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isNotEmpty()) {
            sb.append("\n\nHow to work:")
            steps.forEachIndexed { i, s -> sb.append('\n').append(i + 1).append(". ").append(s) }
        }
        val constraints = req.constraints.map { it.trim() }.filter { it.isNotEmpty() }
        if (constraints.isNotEmpty()) {
            sb.append("\n\nConstraints:")
            constraints.forEach { c -> sb.append("\n- ").append(c) }
        }
        val out = req.outputFormat.trim()
        if (out.isNotEmpty()) sb.append("\n\nReturn exactly: ").append(out)
        if (sb.length == 0) return ""
        sb.append("\n\nWork inside this context only; you cannot see the parent conversation. ")
        sb.append("Say what you verified and what you could not, and keep the final answer tight.")
        return sb.toString().take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)
    }

    fun build(
        req: Request,
        sortOrder: Int = Int.MAX_VALUE,
    ): SubAgentDefinition = SubAgentDefinition(
        name = req.normalizedName,
        description = descriptionOf(req),
        instructions = bodyOf(req),
        modelGroupId = req.modelGroupId,
        thinkingLevelOverride = thinkingOf(req.thinking),
        isBuiltIn = false,
        sortOrder = sortOrder,
        updatedAt = System.currentTimeMillis(),
    )

    fun thinkingOf(raw: String?): com.openminis.app.data.model.ThinkingLevel? =
        when (raw?.trim()?.lowercase()) {
            "low", "quick" -> com.openminis.app.data.model.ThinkingLevel.LOW
            "medium", "normal" -> com.openminis.app.data.model.ThinkingLevel.MEDIUM
            "high", "deep" -> com.openminis.app.data.model.ThinkingLevel.HIGH
            else -> null
        }

    fun match(existing: List<SubAgentDefinition>, nameOrId: String): SubAgentDefinition? {
        val key = nameOrId.trim()
        if (key.isEmpty()) return null
        return existing.firstOrNull { it.id == key }
            ?: existing.firstOrNull { it.name.trim().equals(key, ignoreCase = true) }
    }

    fun renderRoster(existing: List<SubAgentDefinition>): String = buildString {
        append("Sub agents in this roster (").append(existing.count { !it.isBuiltIn }).append(" custom, ")
        append("limit ").append(SubAgentLimits.MAX_COUNT - 1).append("):\n")
        for (def in existing) {
            append("- ").append(def.name).append(if (def.isBuiltIn) " (built in)" else "")
            append(" — ").append(def.description.take(160))
            append(" [id=").append(def.id).append("]\n")
        }
        append("\nDelegate with subagent_task, naming one of these in `agent`.")
    }
}
