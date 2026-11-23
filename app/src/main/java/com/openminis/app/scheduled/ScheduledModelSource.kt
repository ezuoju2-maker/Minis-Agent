package com.openminis.app.scheduled

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import org.json.JSONObject

object ScheduledModelSource {

    data class EntryRef(val entryId: String, val modelId: String, val visible: Boolean)
    data class GroupRef(val groupId: String)

    data class Resolved(val bindingJson: String, val modelId: String?, val display: String)

    private const val LOOKUP_HINT = "run `minis-config get models` for entry ids, then pass entry:<id> or group:<id>"

    fun resolve(raw: String, entries: List<EntryRef>, groups: List<GroupRef>): Resolved {
        val s = raw.trim()
        require(s.isNotEmpty()) { "--model is empty; $LOOKUP_HINT" }
        if (s.startsWith("group:")) {
            val gid = s.removePrefix("group:")
            require(gid.isNotEmpty()) { "--model group id is empty" }
            require(groups.any { it.groupId == gid }) { "--model: no model group with id '$gid'" }
            return Resolved("""{"type":"group","groupId":"$gid"}""", null, "group:$gid")
        }
        if (s.startsWith("entry:")) {
            val eid = s.removePrefix("entry:")
            require(eid.isNotEmpty()) { "--model entry id is empty; $LOOKUP_HINT" }
            val e = entries.firstOrNull { it.entryId == eid }
                ?: throw IllegalArgumentException("--model: no model entry with id '$eid'; $LOOKUP_HINT")
            return entry(e)
        }
        entries.firstOrNull { it.entryId == s }?.let { return entry(it) }
        val serving = entries.filter { it.visible && it.modelId == s }
        return when (serving.size) {
            1 -> entry(serving.single())
            0 -> throw IllegalArgumentException("--model: '$s' is not a model entry, group or visible model id; $LOOKUP_HINT")
            else -> throw IllegalArgumentException(
                "--model: '$s' is served by ${serving.size} model entries; pick one: " +
                    serving.joinToString(", ") { "entry:${it.entryId}" },
            )
        }
    }

    fun healLegacy(modelId: String, entries: List<EntryRef>, groups: List<GroupRef>): Resolved? {
        val s = modelId.trim()
        val named = s.startsWith("entry:") || s.startsWith("group:") || entries.any { it.entryId == s }
        if (!named) return null
        return runCatching { resolve(s, entries, groups) }.getOrNull()
    }

    fun display(bindingJson: String?, modelId: String?): String? {
        val o = bindingJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        when (o?.optString("type")) {
            "entry" -> o.optString("entryId").takeIf { it.isNotEmpty() }?.let { return "entry:$it" }
            "group" -> o.optString("groupId").takeIf { it.isNotEmpty() }?.let { return "group:$it" }
        }
        return modelId
    }

    fun entryRefs(entries: List<ModelEntry>, visible: List<ModelEntry>): List<EntryRef> {
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        return entries.map { EntryRef(it.id, it.baseModel.id, it.id in visibleIds) }
    }

    fun groupRefs(groups: List<ModelGroup>): List<GroupRef> = groups.map { GroupRef(it.id) }

    private fun entry(e: EntryRef) = Resolved(
        """{"type":"entry","entryId":"${e.entryId}"}""",
        e.modelId,
        "entry:${e.entryId}",
    )
}
