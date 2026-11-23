package com.openminis.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

object SubAgentLimits {
    const val MAX_COUNT = 10
    const val NAME_MAX_LENGTH = 40

    const val DESCRIPTION_MAX_LENGTH = 200

    const val INSTRUCTIONS_MAX_LENGTH = 4000
}

@Serializable
data class SubAgentDefinition(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var description: String,
    var instructions: String = "",
    var modelGroupId: String? = null,
    var thinkingLevelOverride: ThinkingLevel? = null,
    val isBuiltIn: Boolean = false,
    var sortOrder: Int = 0,
    @kotlinx.serialization.Serializable(with = com.openminis.app.backup.Iso8601MillisSerializer::class)
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun displayName(context: android.content.Context): String =
        if (isBuiltIn) context.getString(com.openminis.app.R.string.sub_agent_builtin_name) else name

    fun displayDescription(context: android.content.Context): String =
        if (isBuiltIn) context.getString(com.openminis.app.R.string.sub_agent_builtin_description) else description

    fun clamped(): SubAgentDefinition = copy(
        name = name.take(SubAgentLimits.NAME_MAX_LENGTH),
        description = description.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH),
        instructions = instructions.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH),
    )

    val exceedsLimits: Boolean
        get() = name.length > SubAgentLimits.NAME_MAX_LENGTH ||
            description.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH ||
            instructions.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH

    companion object {
        const val BUILT_IN_ID = "builtin.general"

        const val TOOL_NAME = "subagent_task"

        const val BUILT_IN_NAME = "General Sub Agent"

        const val BUILT_IN_DESCRIPTION =
            "Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, " +
                "digesting bulk output into a conclusion, or running independent branches in parallel."

        fun makeBuiltIn(sortOrder: Int = 0): SubAgentDefinition = SubAgentDefinition(
            id = BUILT_IN_ID,
            name = BUILT_IN_NAME,
            description = BUILT_IN_DESCRIPTION,
            instructions = "",
            modelGroupId = null,
            isBuiltIn = true,
            sortOrder = sortOrder,
        )
    }
}

object SubAgentRoster {

    fun normalize(
        input: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): List<SubAgentDefinition> {
        val list = input.toMutableList()

        val builtInIndex = list.indexOfFirst { it.id == SubAgentDefinition.BUILT_IN_ID }
        var builtIn: SubAgentDefinition
        if (builtInIndex >= 0) {
            builtIn = list.removeAt(builtInIndex)
            if (builtIn.name != SubAgentDefinition.BUILT_IN_NAME ||
                builtIn.description != SubAgentDefinition.BUILT_IN_DESCRIPTION
            ) {
                log?.invoke("[SubAgents] restoring the built-in's canonical name/description")
                builtIn = builtIn.copy(
                    name = SubAgentDefinition.BUILT_IN_NAME,
                    description = SubAgentDefinition.BUILT_IN_DESCRIPTION,
                )
            }
        } else {
            builtIn = SubAgentDefinition.makeBuiltIn()
            log?.invoke("[SubAgents] built-in definition missing — reinserting")
        }

        list.sortWith(compareBy({ it.sortOrder }, { it.id }))

        val allowedCustom = maxOf(0, SubAgentLimits.MAX_COUNT - 1)
        var custom: List<SubAgentDefinition> = list
        if (custom.size > allowedCustom) {
            val dropped = custom.drop(allowedCustom).map { it.name }
            log?.invoke(
                "[SubAgents] roster over the limit — keeping $allowedCustom of ${custom.size} " +
                    "custom definitions, dropping: ${dropped.joinToString(", ")}",
            )
            custom = custom.take(allowedCustom)
        }

        if (builtIn.exceedsLimits || custom.any { it.exceedsLimits }) {
            log?.invoke("[SubAgents] one or more definitions exceeded field limits — truncating")
        }

        val out = mutableListOf(builtIn.clamped().copy(sortOrder = 0))
        val seenNames = mutableSetOf(nameKey(builtIn.name))
        var next = 1
        for (def in custom) {
            if (def.isBuiltIn || def.id == SubAgentDefinition.BUILT_IN_ID) continue
            if (def.name.isBlank()) continue
            if (seenNames.contains(nameKey(def.name))) continue
            seenNames.add(nameKey(def.name))
            out.add(def.clamped().copy(sortOrder = next))
            next += 1
        }
        return out
    }

    fun resolve(name: String?, roster: List<SubAgentDefinition>): SubAgentDefinition? {
        val raw = name?.let { nameKey(it) }
        if (raw.isNullOrEmpty()) {
            return roster.firstOrNull { it.id == SubAgentDefinition.BUILT_IN_ID } ?: roster.firstOrNull()
        }
        return roster.firstOrNull { nameKey(it.name) == raw }
    }

    fun nameKey(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    data class BackupMerge(val roster: List<SubAgentDefinition>, val written: Int, val skipped: Int)

    fun mergeBackup(
        local: List<SubAgentDefinition>,
        incoming: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): BackupMerge {
        val out = local.toMutableList()
        var written = 0
        var skipped = 0
        for (r in incoming) {
            if (r.isBuiltIn || r.id == SubAgentDefinition.BUILT_IN_ID) { skipped++; continue }
            val at = out.indexOfFirst { it.id == r.id }
            if (at >= 0) {
                if (r.updatedAt > out[at].updatedAt) { out[at] = r.copy(isBuiltIn = false); written++ } else skipped++
                continue
            }
            if (out.any { nameKey(it.name) == nameKey(r.name) }) {
                log?.invoke("backup sub agent '${r.name}' skipped: a local agent already has that name")
                skipped++
                continue
            }
            out.add(r.copy(isBuiltIn = false))
            written++
        }
        return BackupMerge(normalize(out, log), written, skipped)
    }
}
