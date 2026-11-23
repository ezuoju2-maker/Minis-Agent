package com.openminis.app.backup

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

@Serializable
data class BackupSubAgentRecord(
    val id: String,
    val name: String,
    val subAgentDescription: String,
    val instructions: String,
    val modelGroupId: String? = null,
    val sortOrder: Int,
    val updatedAt: String,
    val thinkingLevelOverride: String? = null,
)

internal object BackupSubAgentMapping {

    const val FILE_BASE = "sub_agents"
    const val RECORD_TYPE = "SubAgentV1"

    fun exportable(roster: List<SubAgentDefinition>): List<SubAgentDefinition> =
        roster.filter { !it.isBuiltIn && it.id != SubAgentDefinition.BUILT_IN_ID }

    fun toRecord(def: SubAgentDefinition): BackupSubAgentRecord = BackupSubAgentRecord(
        id = def.id,
        name = def.name,
        subAgentDescription = def.description,
        instructions = def.instructions,
        modelGroupId = def.modelGroupId,
        sortOrder = def.sortOrder,
        updatedAt = Instant.ofEpochMilli(def.updatedAt).truncatedTo(ChronoUnit.SECONDS).toString(),
        thinkingLevelOverride = def.thinkingLevelOverride?.name?.lowercase(),
    )

    fun fromRecord(r: BackupSubAgentRecord): SubAgentDefinition? {
        if (r.id.isBlank() || r.id == SubAgentDefinition.BUILT_IN_ID || r.name.isBlank()) return null
        return SubAgentDefinition(
            id = r.id,
            name = r.name,
            description = r.subAgentDescription,
            instructions = r.instructions,
            modelGroupId = r.modelGroupId?.takeIf { it.isNotBlank() },
            thinkingLevelOverride = r.thinkingLevelOverride?.let { ThinkingLevel.parseOrNull(it) },
            isBuiltIn = false,
            sortOrder = r.sortOrder,
            updatedAt = parseMillis(r.updatedAt),
        )
    }

    internal fun parseMillis(iso: String): Long =
        runCatching { Instant.parse(iso).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .getOrDefault(0L)
}
