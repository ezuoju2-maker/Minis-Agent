package com.openminis.app.agent.jobs

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

enum class SubAgentModelChoice(val wire: String) {
    SAME_AS_PARENT("same_as_me"),

    DEFAULT_MODEL("default_model"),

    SUB_MODEL("sub_model"),
    ;

    companion object {
        fun parse(raw: String?): SubAgentModelChoice {
            val v = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == v } ?: SAME_AS_PARENT
        }
    }
}

enum class HelperModelTier(val wire: String) {
    PRIMARY("primary"), SUB("sub");

    companion object {
        fun parse(raw: String?): HelperModelTier = when (raw?.trim()?.lowercase()) {
            "sub" -> SUB
            else -> PRIMARY
        }
    }
}

enum class HelperModelOrigin(val wire: String) {
    PINNED("pinned"),

    INHERITED("inherited"),

    DEFAULT_GROUP("default_group"),

    SUB_GROUP("sub_group"),
}

data class HelperModelResolution(
    val bindingJson: String?,
    val seedModelId: String,
    val modelLabel: String,
    val tierUsed: HelperModelTier,
    val origin: HelperModelOrigin = HelperModelOrigin.INHERITED,
    val modelGroupUnavailable: Boolean = false,
    val modelGroupName: String? = null,
    val resolvedEntryId: String? = null,
    val resolvedProviderLabel: String? = null,
    val resolvedProviderType: String? = null,
    val resolvedModelId: String? = null,
    val resolvedModelName: String? = null,
)

object ModelTierResolver {
    private const val TAG = "ModelTierResolver"

    fun resolve(
        tier: HelperModelTier,
        repo: ProviderRepository,
        parentBindingJson: String?,
        parentModelId: String?,
        parentActiveEntryId: String?,
    ): HelperModelResolution? {
        fun primary(): HelperModelResolution? {
            val entries = repo.config.value.modelEntries
            if (parentBindingJson != null && parentModelId != null) {
                val label = entries.firstOrNull { it.model.id == parentModelId }?.model?.displayName ?: parentModelId
                return HelperModelResolution(parentBindingJson, parentModelId, label, HelperModelTier.PRIMARY)
            }
            val entry: ModelEntry? = parentActiveEntryId?.let { id -> entries.firstOrNull { it.id == id } }
                ?: parentModelId?.let { mid -> entries.firstOrNull { it.model.id == mid } }
            if (entry == null) {
                AppLogger.warning(TAG, "resolve(primary): parent has no resolvable model")
                return null
            }
            val binding = JSONObject().put("type", "entry").put("entryId", entry.id).toString()
            return HelperModelResolution(binding, entry.model.id, entry.model.displayName, HelperModelTier.PRIMARY)
        }

        return when (tier) {
            HelperModelTier.PRIMARY -> primary()
            HelperModelTier.SUB -> {
                val gid = repo.defaultSubGroupId
                val group = gid?.let { repo.group(it) }
                if (group == null) {
                    AppLogger.info(TAG, "resolve(sub): no Sub group configured — degrading to primary")
                    return primary()
                }
                val member = repo.availableMemberEntries(group).firstOrNull()
                if (member == null) {
                    AppLogger.info(TAG, "resolve(sub): Sub group '${group.name}' has no usable member — degrading to primary")
                    return primary()
                }
                val binding = JSONObject().put("type", "group").put("groupId", gid).toString()
                HelperModelResolution(
                    binding, member.model.id, member.model.displayName, HelperModelTier.SUB,
                    origin = HelperModelOrigin.SUB_GROUP,
                    modelGroupName = group.name,
                    resolvedEntryId = member.id,
                    resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                    resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                    resolvedModelId = member.model.id,
                    resolvedModelName = member.model.displayName,
                )
            }
        }
    }

    fun resolveForSubAgent(
        pinnedGroupId: String?,
        choice: SubAgentModelChoice,
        repo: ProviderRepository,
        parentBindingJson: String?,
        parentModelId: String?,
        parentActiveEntryId: String?,
    ): HelperModelResolution? {
        fun inherited() = resolve(
            HelperModelTier.PRIMARY, repo, parentBindingJson, parentModelId, parentActiveEntryId,
        )

        if (pinnedGroupId != null) {
            val group = repo.group(pinnedGroupId)
            val member = group?.let { repo.availableMemberEntries(it).firstOrNull() }
            if (group != null && member != null) {
                val binding = JSONObject().put("type", "group").put("groupId", pinnedGroupId).toString()
                return HelperModelResolution(
                    binding, member.model.id, member.model.displayName, HelperModelTier.PRIMARY,
                    origin = HelperModelOrigin.PINNED,
                    modelGroupName = group.name,
                    resolvedEntryId = member.id,
                    resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                    resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                    resolvedModelId = member.model.id,
                    resolvedModelName = member.model.displayName,
                )
            }
            AppLogger.warning(
                TAG,
                "resolveForSubAgent: pinned group '$pinnedGroupId' " +
                    (if (group == null) "no longer exists" else "has no usable member") +
                    " — inheriting the parent's model instead",
            )
            return inherited()?.copy(modelGroupUnavailable = true)
        }

        return when (choice) {
            SubAgentModelChoice.SAME_AS_PARENT -> inherited()
            SubAgentModelChoice.DEFAULT_MODEL -> {
                val gid = repo.defaultPrimaryGroupId
                val group = gid?.let { repo.group(it) }
                val member = group?.let { repo.availableMemberEntries(it).firstOrNull() }
                if (gid == null || member == null) {
                    AppLogger.info(TAG, "resolveForSubAgent(default_model): unroutable — inheriting")
                    inherited()
                } else {
                    val binding = JSONObject().put("type", "group").put("groupId", gid).toString()
                    HelperModelResolution(
                        binding, member.model.id, member.model.displayName, HelperModelTier.PRIMARY,
                        origin = HelperModelOrigin.DEFAULT_GROUP,
                        modelGroupName = group.name,
                        resolvedEntryId = member.id,
                        resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                        resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                        resolvedModelId = member.model.id,
                        resolvedModelName = member.model.displayName,
                    )
                }
            }
            SubAgentModelChoice.SUB_MODEL ->
                resolve(HelperModelTier.SUB, repo, parentBindingJson, parentModelId, parentActiveEntryId)
        }
    }
}
