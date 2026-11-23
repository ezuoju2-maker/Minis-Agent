package com.openminis.app.config.collections

import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ConfigRefKind
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.ReadableThinkingLabels
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository

class SubAgentsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "subagents"
    override val displayName: String get() = "Sub agents"
    override val description: String get() =
        "Named sub agents the assistant can delegate to (Settings › Sub Agents). The built-in one cannot be deleted and its name/description are fixed."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true

    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    private fun realId(segment: String): String =
        if (segment == BUILT_IN_ALIAS) SubAgentDefinition.BUILT_IN_ID else segment

    private fun def(id: String): SubAgentDefinition? = repo.subAgent(id)


    override fun childIds(): List<String> = repo.subAgents.map { pathSegment(it.id) }

    override fun fields(forId: String): List<ConfigField> {
        val id = realId(forId)
        val d = def(id) ?: return emptyList()
        val seg = pathSegment(id)
        return listOf(
            nameField(id, seg, d.isBuiltIn),
            descriptionField(id, seg, d.isBuiltIn),
            instructionsField(id, seg, d.isBuiltIn),
            modelGroupField(id, seg),
            thinkingField(id, seg),
            builtInField(id, seg),
        )
    }


    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected a JSON object")
        val name = checkedName((obj["name"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`name` is required"), excluding = null)
        val desc = checkedDescription((obj["description"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue(
                "`description` is required — it is what the assistant reads to decide when to use this sub agent",
            ))
        val instructions = (obj["instructions"] as? ConfigValue.Str)?.value?.let(::checkedInstructions).orEmpty()
        val groupId = (obj["model_group"] as? ConfigValue.Str)?.value?.trim()
            ?.takeIf { it.isNotEmpty() }?.let(::resolveGroupId)
        val thinking = (obj["thinking"] as? ConfigValue.Str)?.value?.let(::parseThinking)
        if (repo.subAgents.size >= SubAgentLimits.MAX_COUNT) {
            throw ConfigError.InvalidValue(
                "You can define at most ${SubAgentLimits.MAX_COUNT} sub agents (including the built-in one). Remove one first.",
            )
        }
        val created = SubAgentDefinition(
            name = name,
            description = desc,
            instructions = instructions,
            modelGroupId = groupId,
            thinkingLevelOverride = thinking,
            isBuiltIn = false,
            sortOrder = Int.MAX_VALUE,
        )
        repo.upsertSubAgent(created)

        val pos = (obj["position"] as? ConfigValue.Int)?.value
        if (pos != null && pos >= 0) {
            val ids = customIds(repo).toMutableList()
            if (ids.remove(created.id)) {
                ids.add(minOf(pos, ids.size), created.id)
                repo.reorderSubAgents(listOf(SubAgentDefinition.BUILT_IN_ID) + ids)
            }
        }
        return created.id
    }

    override fun remove(id: String) {
        val real = realId(id)
        val d = def(real) ?: throw ConfigError.UnknownPath("subagents.$id")
        if (d.isBuiltIn) {
            throw ConfigError.PermissionDenied(
                "The built-in sub agent is the target of every delegation that names no agent, so it cannot be deleted. You can change its model and instructions instead.",
            )
        }
        repo.deleteSubAgent(real)
    }


    override val readableSubjectFormat: String get() = "Sub agent “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = def(realId(id))?.name

    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        val name = (d["name"] as? ConfigValue.Str)?.value ?: "?"
        val lines = mutableListOf(ReadableLine("Create sub agent “%@”", listOf(ReadableArg.Text(name))))
        (d["model_group"] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }?.let {
            lines.add(ReadableLine("Model group: %@", listOf(groupPhrase(ConfigValue.Str(it)))))
        }
        return lines
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val d = def(realId(id)) ?: return emptyList()
        return listOf(ReadableLine("Delete sub agent “%@”", listOf(ReadableArg.Text(d.name))))
    }

    private fun groupPhrase(v: ConfigValue): ReadableArg {
        val s = (v as? ConfigValue.Str)?.value?.trim() ?: return ConfigReadableRenderer.genericPhrase(v)
        if (s.isEmpty()) return ReadableArg.Key("Auto")
        return ReadableArg.Text(repo.config.value.modelGroups.firstOrNull { it.id == s }?.name ?: s)
    }


    private fun checkedName(raw: String, excluding: String?): String {
        val name = raw.trim()
        if (name.isEmpty()) throw ConfigError.InvalidValue("`name` must not be empty")
        if (name.length > SubAgentLimits.NAME_MAX_LENGTH) {
            throw ConfigError.InvalidValue("`name` must be at most ${SubAgentLimits.NAME_MAX_LENGTH} characters")
        }
        val key = SubAgentRoster.nameKey(name)
        if (repo.subAgents.any { it.id != excluding && SubAgentRoster.nameKey(it.name) == key }) {
            throw ConfigError.InvalidValue(
                "Another sub agent is already called '$name'. Names are how the model addresses an agent, so they must be unique.",
            )
        }
        return name
    }

    private fun checkedDescription(raw: String): String {
        val desc = raw.trim()
        if (desc.isEmpty()) throw ConfigError.InvalidValue("`description` must not be empty")
        if (desc.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH) {
            throw ConfigError.InvalidValue(
                "`description` must be at most ${SubAgentLimits.DESCRIPTION_MAX_LENGTH} characters — it costs main-conversation tokens on every turn",
            )
        }
        return desc
    }

    private fun checkedInstructions(raw: String): String {
        val text = raw.trim()
        if (text.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) {
            throw ConfigError.InvalidValue(
                "`instructions` must be at most ${SubAgentLimits.INSTRUCTIONS_MAX_LENGTH} characters",
            )
        }
        return text
    }

    private fun parseThinking(raw: String): ThinkingLevel? {
        val token = raw.trim().lowercase()
        if (token.isEmpty()) return null
        return THINKING_TOKENS.entries.firstOrNull { it.value == token }?.key
            ?: throw ConfigError.InvalidValue(
                "`thinking` must be one of: ${THINKING_TOKENS.values.joinToString(", ")} — or \"\" to unset it",
            )
    }

    private fun resolveGroupId(raw: String): String {
        val trimmed = raw.trim()
        val groups = repo.config.value.modelGroups
        if (groups.any { it.id == trimmed }) return trimmed
        val key = SubAgentRoster.nameKey(trimmed)
        val matches = groups.filter { SubAgentRoster.nameKey(it.name) == key }
        if (matches.size == 1) return matches[0].id
        if (matches.size > 1) {
            throw ConfigError.InvalidValue("More than one model group is called '$trimmed' — use its id instead.")
        }
        val known = groups.joinToString(", ") { it.name }.ifEmpty { "(none configured)" }
        throw ConfigError.InvalidValue("Unknown model group '$trimmed'. Available: $known")
    }

    private fun mutate(id: String, apply: (SubAgentDefinition) -> SubAgentDefinition) {
        val current = def(id) ?: throw ConfigError.UnknownPath("subagents.${pathSegment(id)}")
        repo.upsertSubAgent(apply(current))
    }

    private fun builtInFieldDenied(field: String) = ConfigError.PermissionDenied(
        "The built-in sub agent's $field is fixed: the assistant matches on the name and reads the description to decide what to delegate, and both are restored on every load. Change its model or instructions instead, or add your own sub agent.",
    )


    private fun nameField(id: String, seg: String, isBuiltIn: Boolean): ConfigField =
        ClosureField(
            path = "subagents.$seg.name",
            displayName = "Name",
            description = if (isBuiltIn) {
                "Fixed for the built-in sub agent — it is the value the assistant passes as `agent`."
            } else {
                "How the assistant addresses this sub agent. Must be unique; at most ${SubAgentLimits.NAME_MAX_LENGTH} characters."
            },
            valueSchema = ConfigSchema.Str(maxLength = SubAgentLimits.NAME_MAX_LENGTH),
            access = if (isBuiltIn) ConfigAccess.READONLY else ConfigAccess.READWRITE,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text()),
            reader = { def(id)?.let { ConfigValue.Str(it.name) } ?: ConfigValue.Null },
            writer = { v ->
                if (isBuiltIn) throw builtInFieldDenied("name")
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val name = checkedName(s, excluding = id)
                mutate(id) { it.copy(name = name) }
            },
        )

    private fun descriptionField(id: String, seg: String, isBuiltIn: Boolean): ConfigField =
        ClosureField(
            path = "subagents.$seg.description",
            displayName = "Description",
            description = if (isBuiltIn) {
                "Fixed for the built-in sub agent — it is what the assistant reads to decide what to delegate."
            } else {
                "When to use this sub agent. The assistant picks by this text, so write it as \"use this when …\"; at most ${SubAgentLimits.DESCRIPTION_MAX_LENGTH} characters. Details belong in instructions."
            },
            valueSchema = ConfigSchema.Str(maxLength = SubAgentLimits.DESCRIPTION_MAX_LENGTH),
            access = if (isBuiltIn) ConfigAccess.READONLY else ConfigAccess.READWRITE,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text(preview = 60)),
            reader = { def(id)?.let { ConfigValue.Str(it.description) } ?: ConfigValue.Null },
            writer = { v ->
                if (isBuiltIn) throw builtInFieldDenied("description")
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val desc = checkedDescription(s)
                mutate(id) { it.copy(description = desc) }
            },
        )

    private fun instructionsField(id: String, seg: String, isBuiltIn: Boolean): ConfigField =
        ClosureField(
            path = "subagents.$seg.instructions",
            displayName = "Instructions",
            description = if (isBuiltIn) {
                "Standing instructions appended to every delegation that does not name a sub agent. Custom sub agents use their own instead — these are NOT prepended to them. At most ${SubAgentLimits.INSTRUCTIONS_MAX_LENGTH} characters."
            } else {
                "Standing instructions appended to this sub agent's brief. Only this sub agent uses them. At most ${SubAgentLimits.INSTRUCTIONS_MAX_LENGTH} characters. Empty to clear."
            },
            valueSchema = ConfigSchema.Str(maxLength = SubAgentLimits.INSTRUCTIONS_MAX_LENGTH),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.LongText),
            reader = { def(id)?.let { ConfigValue.Str(it.instructions) } ?: ConfigValue.Null },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val text = checkedInstructions(s)
                mutate(id) { it.copy(instructions = text) }
            },
        )

    private fun modelGroupField(id: String, seg: String): ConfigField =
        ClosureField(
            path = "subagents.$seg.model_group",
            displayName = "Model group",
            description = "Model Group this sub agent runs on, by id or name. Empty string = Auto: the assistant chooses per task between this conversation's model, the default group and the light group.",
            valueSchema = ConfigSchema.Str(),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Custom(::groupPhrase)),
            reader = {
                val d = def(id) ?: return@ClosureField ConfigValue.Null
                val gid = d.modelGroupId ?: return@ClosureField ConfigValue.Str("")
                ConfigValue.Str(repo.config.value.modelGroups.firstOrNull { it.id == gid }?.name ?: gid)
            },
            writer = { v ->
                val raw = ((v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")).trim()
                val gid = if (raw.isEmpty()) null else resolveGroupId(raw)
                mutate(id) { it.copy(modelGroupId = gid) }
            },
        )

    private fun thinkingField(id: String, seg: String): ConfigField =
        ClosureField(
            path = "subagents.$seg.thinking",
            displayName = "Reasoning override",
            description = "Reasoning level every run of this sub agent uses, overriding its Model Group's default: ${THINKING_TOKENS.values.joinToString(", ")}. Empty string = not set: follow the Model Group, or the delegating conversation when the model is Auto. Models that cannot reason this hard are clamped to their own ceiling.",
            valueSchema = ConfigSchema.Str(),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.EnumLabels(ReadableThinkingLabels.withNotSet)),
            reader = {
                val d = def(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(d.thinkingLevelOverride?.let { THINKING_TOKENS[it] } ?: "")
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val level = parseThinking(s)
                mutate(id) { it.copy(thinkingLevelOverride = level) }
            },
        )

    private fun builtInField(id: String, seg: String): ConfigField =
        ReadOnlyField(
            path = "subagents.$seg.built_in",
            displayName = "Built-in",
            description = "True for the general sub agent, which cannot be deleted and whose name and description are fixed.",
            valueSchema = ConfigSchema.Bool,
            reader = { def(id)?.let { ConfigValue.Bool(it.isBuiltIn) } ?: ConfigValue.Null },
        )

    companion object {
        const val BUILT_IN_ALIAS = "general"

        private val THINKING_TOKENS: Map<ThinkingLevel, String> = linkedMapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high",
            ThinkingLevel.XHIGH to "xhigh",
            ThinkingLevel.MAX to "max",
            ThinkingLevel.ULTRA to "ultra",
        )

        fun pathSegment(id: String): String =
            if (id == SubAgentDefinition.BUILT_IN_ID) BUILT_IN_ALIAS else id

        private fun customIds(repo: ProviderRepository): List<String> =
            repo.subAgents.filter { !it.isBuiltIn }.map { it.id }

        fun orderField(repo: ProviderRepository): ConfigField =
            ClosureField(
                path = "subagents.order",
                displayName = "Sub agent order",
                description = "Ordered ids of the CUSTOM sub agents. This is the order the assistant sees them in; the built-in one is always first and is not part of this list.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.SubAgent, ordered = true)),
                reader = { ConfigValue.Arr(customIds(repo).map { ConfigValue.Str(it) }) },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val current = customIds(repo)
                    if (ids.size != current.size || ids.toSet() != current.toSet()) {
                        throw ConfigError.InvalidValue(
                            "Must be a permutation of the ${current.size} custom sub agent id(s). The built-in one is always first and must not appear.",
                        )
                    }
                    repo.reorderSubAgents(listOf(SubAgentDefinition.BUILT_IN_ID) + ids)
                },
            )

        fun summaryField(repo: ProviderRepository): ConfigField =
            ReadOnlyField(
                path = "subagents",
                displayName = "Sub agents (summary)",
                description = "Read-only roster view. Each entry's writable paths are subagents.<id>.{name,description,instructions,model_group,thinking}; the built-in one refuses name/description and cannot be removed. Reorder with subagents.order.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val groups = repo.config.value.modelGroups
                    val roster = repo.subAgents
                    val items = roster.map { d ->
                        val obj = linkedMapOf<String, ConfigValue>(
                            "id" to ConfigValue.Str(d.id),
                            "name" to ConfigValue.Str(d.name),
                            "description" to ConfigValue.Str(d.description),
                            "built_in" to ConfigValue.Bool(d.isBuiltIn),
                            "path" to ConfigValue.Str("subagents.${pathSegment(d.id)}"),
                        )
                        if (d.instructions.isNotEmpty()) obj["instructions"] = ConfigValue.Str(d.instructions)
                        val gid = d.modelGroupId
                        if (gid != null) {
                            obj["model_group"] = ConfigValue.Str(groups.firstOrNull { it.id == gid }?.name ?: gid)
                        } else {
                            obj["model_group"] = ConfigValue.Str("")
                            obj["model"] = ConfigValue.Str("auto — chosen per task")
                        }
                        d.thinkingLevelOverride?.let { obj["thinking"] = ConfigValue.Str(THINKING_TOKENS[it] ?: "") }
                        if (d.isBuiltIn) {
                            obj["note"] = ConfigValue.Str(
                                "Built-in: name and description are fixed and it cannot be deleted; model and instructions are writable.",
                            )
                        }
                        ConfigValue.Obj(obj)
                    }
                    ConfigValue.Obj(
                        linkedMapOf(
                            "count" to ConfigValue.Int(items.size),
                            "max" to ConfigValue.Int(SubAgentLimits.MAX_COUNT),
                            "agents" to ConfigValue.Arr(items),
                        ),
                    )
                },
            )
    }
}
