package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_instances")
data class ProviderInstanceEntity(
    @PrimaryKey val id: String,
    val label: String,
    @ColumnInfo(name = "provider_type") val providerType: String,
    @ColumnInfo(name = "credential_type") val credentialType: String,
    @ColumnInfo(name = "custom_base_url") val customBaseURL: String? = null,
    @ColumnInfo(name = "append_v1_suffix") val appendV1Suffix: Int = 1,
    @ColumnInfo(name = "use_responses_api") val useResponsesAPI: Int = 0,
    @ColumnInfo(name = "azure_mode") val azureMode: Int = 0,
    @ColumnInfo(name = "image_endpoint_mode") val imageEndpointMode: String? = null,
    @ColumnInfo(name = "image_endpoint_resolved") val imageEndpointResolved: String? = null,
    @ColumnInfo(name = "custom_user_agent") val customUserAgent: String? = null,
    @ColumnInfo(name = "is_enabled") val isEnabled: Int = 1,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
