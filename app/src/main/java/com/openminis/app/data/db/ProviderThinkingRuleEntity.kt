package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "provider_thinking_rules",
    indices = [Index("provider_instance_id")],
)
data class ProviderThinkingRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_instance_id") val providerInstanceId: String,
    val label: String,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "scope_pattern") val scopePattern: String? = null,
    @ColumnInfo(name = "wire_format_json") val wireFormatJson: String? = null,
    @ColumnInfo(name = "reasoning_echo_json") val reasoningEchoJson: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
