package com.openminis.app.data.db

data class UsageRowRecord(
    val createdAt: Long,
    val sessionId: String,
    val modelId: String?,
    val sessionModelId: String?,
    val modelDisplayName: String?,
    val providerType: String?,
    val providerInstanceId: String?,
    val tokenUsage: String?,
)
