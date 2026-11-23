package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_model_groups")
data class ProviderModelGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val strategy: String,
    @ColumnInfo(name = "fallback_strategy") val fallbackStrategy: String,
    @ColumnInfo(name = "default_thinking_level") val defaultThinkingLevel: String? = null,
    @ColumnInfo(name = "context_limit_tokens") val contextLimitTokens: Int? = null,
    @ColumnInfo(name = "last_context_limit_tokens") val lastContextLimitTokens: Int? = null,
    @ColumnInfo(name = "member_entry_ids_json") val memberEntryIdsJson: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
