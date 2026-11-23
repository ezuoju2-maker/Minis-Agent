package com.openminis.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LLMUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    val latestContextTokens: Int = 0,
    val reasoningTokens: Int? = null,
    val cacheReported: Boolean? = null,
    @kotlinx.serialization.Transient
    val chunk: com.openminis.app.usage.UsageChunk? = null,
)
