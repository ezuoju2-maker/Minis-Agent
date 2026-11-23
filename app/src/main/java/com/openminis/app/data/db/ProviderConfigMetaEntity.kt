package com.openminis.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_config_meta")
data class ProviderConfigMetaEntity(
    @PrimaryKey val key: String,
    val value: String?,
)
