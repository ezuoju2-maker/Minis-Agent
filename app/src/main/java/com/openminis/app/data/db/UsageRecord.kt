package com.openminis.app.data.db

data class UsageRecord(
    val modelId: String?,
    val modelDisplayName: String?,
    val providerType: String?,
    val hasSnapshot: Boolean,
    val tokenUsage: String,
    val createdAt: Long,
    val sessionId: String,
)
