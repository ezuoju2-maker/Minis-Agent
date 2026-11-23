package com.openminis.app.backup

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import org.json.JSONObject

object BackupProviderMerge {

    data class Result(
        val config: ProviderConfig,
        val groupIdRewrites: Map<String, String>,
        val entriesAdded: Int = 0,
        val entriesUpdated: Int = 0,
        val instancesAdded: Set<String> = emptySet(),
        val instancesChanged: Set<String> = emptySet(),
        val groupsAdded: Int = 0,
        val groupsChanged: Int = 0,
    )

    data class RestoreCounts(val imported: Int, val updated: Int, val skipped: Int)

    fun counts(result: Result, packageInstanceIds: Collection<String>, credentialsWritten: Collection<String>): RestoreCounts {
        val pkg = packageInstanceIds.toSet()
        val added = result.instancesAdded intersect pkg
        val changed = ((result.instancesChanged + credentialsWritten) intersect pkg) - added
        return RestoreCounts(
            imported = added.size + result.groupsAdded,
            updated = changed.size + result.groupsChanged,
            skipped = pkg.size - added.size - changed.size,
        )
    }

    private fun key(e: ModelEntry) = "${e.providerInstanceId}/${e.baseModel.id}"

    fun merge(local: ProviderConfig, remote: ProviderConfig, log: ((String) -> Unit)? = null): Result {
        val localIds = local.modelEntries.associate { it.uuid to key(it) }
        val remoteIds = remote.modelEntries.associate { it.uuid to key(it) }
        fun localKey(id: String) = localIds[id] ?: id
        fun remoteKey(id: String) = remoteIds[id] ?: id

        val localInstances = local.instances.associateBy { it.id }
        val instances = mutableListOf<ProviderInstance>()
        val placedInstances = mutableSetOf<String>()
        val instancesAdded = mutableSetOf<String>()
        val instancesChanged = mutableSetOf<String>()
        for (ri in remote.instances) {
            if (!placedInstances.add(ri.id)) continue
            val li = localInstances[ri.id]
            if (li == null) {
                instances += ri
                instancesAdded += ri.id
                continue
            }
            val next = ri.copy(
                imageEndpointResolved = if (li.imageEndpointMode == ri.imageEndpointMode) li.imageEndpointResolved else null,
            )
            if (next.copy(createdAt = li.createdAt) != li) instancesChanged += li.id
            instances += next
        }
        for (li in local.instances) if (placedInstances.add(li.id)) instances += li

        val entries = local.modelEntries.toMutableList()
        val indexByKey = HashMap<String, Int>()
        entries.forEachIndexed { i, e -> indexByKey.putIfAbsent(key(e), i) }
        var added = 0
        var updated = 0
        for (re in remote.modelEntries) {
            val k = key(re)
            val i = indexByKey[k]
            if (i == null) {
                indexByKey[k] = entries.size
                entries += re.copy(uuid = k)
                added++
                if (re.providerInstanceId in localInstances) instancesChanged += re.providerInstanceId
                continue
            }
            val le = entries[i]
            var next = le
            if (le.overrides != re.overrides || le.isHidden != re.isHidden) {
                next = next.copy(
                    overrides = re.overrides,
                    isHidden = re.isHidden,
                    userModifiedAt = re.userModifiedAt ?: le.userModifiedAt,
                )
                updated++
            }
            val ln = le.baseModel.displayName
            val rn = re.baseModel.displayName
            if ((ln.isBlank() || ln == le.baseModel.id) && rn.isNotBlank() && rn != re.baseModel.id) {
                next = next.copy(baseModel = next.baseModel.copy(displayName = rn))
            }
            if (next != le) instancesChanged += le.providerInstanceId
            entries[i] = next
        }

        val localGroups = local.modelGroups.map { it.copy(memberEntryIds = it.memberEntryIds.map(::localKey).toMutableList()) }
        val remoteGroups = remote.modelGroups.map { it.copy(memberEntryIds = it.memberEntryIds.map(::remoteKey).toMutableList()) }
        val localById = localGroups.associateBy { it.id }
        val claimed = remoteGroups.map { it.id }.filter { it in localById }.toMutableSet()
        val rewrites = LinkedHashMap<String, String>()
        val groups = mutableListOf<ModelGroup>()
        val placedGroups = mutableSetOf<String>()
        var groupsAdded = 0
        var groupsChanged = 0
        for (rg in remoteGroups) {
            val target = localById[rg.id]
                ?: localGroups.firstOrNull { it.name == rg.name && it.id !in claimed }
                    ?.also {
                        claimed += it.id
                        rewrites[rg.id] = it.id
                        log?.invoke("[Restore] group '${rg.name}' ${rg.id} merged into local ${it.id} (same name)")
                    }
            val id = target?.id ?: rg.id
            if (!placedGroups.add(id)) continue
            if (target == null) {
                groups += rg
                groupsAdded++
                continue
            }
            val next = backupWins(rg, target)
            if (next != target || rg.id != target.id) groupsChanged++
            groups += next
        }
        for (lg in localGroups) if (placedGroups.add(lg.id)) groups += lg

        fun rw(id: String?) = id?.let { rewrites[it] ?: it }
        val agentEntries = (local.agentLoopModelEntryIds.map(::localKey) + remote.agentLoopModelEntryIds.map(::remoteKey)).distinct()
        val agentGroups = (local.agentLoopGroupIds + remote.agentLoopGroupIds).map { rewrites[it] ?: it }.distinct()

        val subAgents = rewriteSubAgents(local.subAgents, rewrites).toMutableList()
        for (r in rewriteSubAgents(remote.subAgents, rewrites)) {
            val clash = subAgents.any { it.id == r.id || SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(r.name) }
            if (!clash) subAgents += r
        }

        val merged = local.copy(
            instances = instances,
            modelEntries = entries,
            modelGroups = groups,
            subAgents = SubAgentRoster.normalize(subAgents, log).toMutableList(),
            agentLoopModelEntryIds = agentEntries.toMutableList(),
            agentLoopGroupIds = agentGroups.toMutableList(),
            defaultPrimaryGroupId = rw(local.defaultPrimaryGroupId),
            defaultSubGroupId = rw(local.defaultSubGroupId),
            voiceInputGroupId = rw(local.voiceInputGroupId),
            voiceOutputGroupId = rw(local.voiceOutputGroupId),
            visionGroupId = rw(local.visionGroupId),
        )
        return Result(
            merged, rewrites, added, updated,
            instancesAdded = instancesAdded,
            instancesChanged = instancesChanged,
            groupsAdded = groupsAdded,
            groupsChanged = groupsChanged,
        )
    }

    private fun backupWins(backup: ModelGroup, local: ModelGroup): ModelGroup {
        val members = backup.memberEntryIds.distinct().toMutableList()
        for (m in local.memberEntryIds) if (m !in members) members += m
        return backup.copy(id = local.id, memberEntryIds = members)
    }

    fun rewriteBindingJson(binding: String?, rewrites: Map<String, String>): String? {
        if (binding.isNullOrBlank() || rewrites.isEmpty()) return binding
        val o = runCatching { JSONObject(binding) }.getOrNull() ?: return binding
        if (o.optString("type") != "group") return binding
        val to = rewrites[o.optString("groupId")] ?: return binding
        return o.put("groupId", to).toString()
    }

    fun rewriteLegacyModelId(modelId: String?, rewrites: Map<String, String>): String? {
        if (modelId == null || !modelId.startsWith("group:")) return modelId
        val to = rewrites[modelId.removePrefix("group:")] ?: return modelId
        return "group:$to"
    }

    fun rewriteSubAgents(agents: List<SubAgentDefinition>, rewrites: Map<String, String>): List<SubAgentDefinition> =
        if (rewrites.isEmpty()) agents else agents.map { a ->
            val to = a.modelGroupId?.let { rewrites[it] }
            if (to == null) a else a.copy(modelGroupId = to)
        }
}
