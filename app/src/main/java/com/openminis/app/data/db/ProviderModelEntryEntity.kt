package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "provider_model_entries",
    foreignKeys = [ForeignKey(
        entity = ProviderInstanceEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_instance_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("provider_instance_id")],
)
data class ProviderModelEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_instance_id") val providerInstanceId: String,
    @ColumnInfo(name = "base_model_json") val baseModelJson: String,
    @ColumnInfo(name = "overrides_json") val overridesJson: String? = null,
    @ColumnInfo(name = "is_custom") val isCustom: Int = 0,
    @ColumnInfo(name = "is_hidden") val isHidden: Int = 0,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "user_modified_at") val userModifiedAt: Long? = null,
)
