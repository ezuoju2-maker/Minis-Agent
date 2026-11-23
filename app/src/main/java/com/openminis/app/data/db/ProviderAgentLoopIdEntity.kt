package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "provider_agent_loop_ids", primaryKeys = ["kind", "target_id"])
data class ProviderAgentLoopIdEntity(
    val kind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
