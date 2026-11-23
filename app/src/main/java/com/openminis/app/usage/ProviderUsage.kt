package com.openminis.app.usage

import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject

data class UsageChunk(
    val input: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cacheKeyPresent: Boolean = false,
    val contextTokens: Long? = null,
) {
    fun toLLMUsage(): LLMUsage = LLMUsage(
        inputTokens = (input ?: 0).toIntSaturated(),
        outputTokens = (output ?: 0).toIntSaturated(),
        cacheCreationInputTokens = cacheWrite?.takeIf { it > 0 }?.toIntSaturated(),
        cacheReadInputTokens = cacheRead?.takeIf { it > 0 }?.toIntSaturated(),
        latestContextTokens = (contextTokens ?: 0).toIntSaturated(),
        reasoningTokens = reasoning?.toIntSaturated(),
        cacheReported = cacheKeyPresent,
        chunk = this,
    )
}

private fun Long.toIntSaturated(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

object ProviderUsageParser {

    fun anthropic(u: JSONObject): UsageChunk {
        val cacheWrite = UsageNumbers.provider(u, "cache_creation_input_tokens")
        val cacheRead = UsageNumbers.provider(u, "cache_read_input_tokens")
        val input = UsageNumbers.provider(u, "input_tokens")
        return UsageChunk(
            input = input,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            output = UsageNumbers.provider(u, "output_tokens"),
            reasoning = null,
            cacheKeyPresent = cacheRead != null || cacheWrite != null,
            contextTokens = input,
        )
    }

    fun openAIChat(u: JSONObject): UsageChunk {
        val details = u.optJSONObject("prompt_tokens_details")
        val cacheRead = maxPresent(
            details?.let { UsageNumbers.provider(it, "cached_tokens") },
            UsageNumbers.provider(u, "prompt_cache_hit_tokens"),
            UsageNumbers.provider(u, "cached_tokens"),
        )
        val cacheWrite = maxPresent(
            details?.let { UsageNumbers.provider(it, "cache_write_tokens") },
            details?.let { UsageNumbers.provider(it, "cache_creation_input_tokens") },
        )
        val prompt = UsageNumbers.provider(u, "prompt_tokens")
        val cr = cacheRead ?: 0
        val cw = cacheWrite ?: 0
        val input = prompt?.let {
            when {
                it - cr - cw >= 0 -> it - cr - cw
                it - cr >= 0 -> it - cr
                else -> it
            }
        }
        return UsageChunk(
            input = input,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            output = UsageNumbers.provider(u, "completion_tokens"),
            reasoning = u.optJSONObject("completion_tokens_details")?.let { UsageNumbers.provider(it, "reasoning_tokens") },
            cacheKeyPresent = cacheRead != null || cacheWrite != null,
            contextTokens = prompt,
        )
    }

    fun openAIResponses(u: JSONObject): UsageChunk {
        val total = UsageNumbers.provider(u, "input_tokens")
        val cacheRead = u.optJSONObject("input_tokens_details")?.let { UsageNumbers.provider(it, "cached_tokens") }
        val input = total?.let { t -> cacheRead?.let { (t - it).takeIf { d -> d >= 0 } } ?: t }
        return UsageChunk(
            input = input,
            cacheRead = cacheRead,
            cacheWrite = null,
            output = UsageNumbers.provider(u, "output_tokens"),
            reasoning = u.optJSONObject("output_tokens_details")?.let { UsageNumbers.provider(it, "reasoning_tokens") },
            cacheKeyPresent = cacheRead != null,
            contextTokens = total,
        )
    }

    fun gemini(u: JSONObject): UsageChunk {
        val prompt = UsageNumbers.provider(u, "promptTokenCount")
        val cached = UsageNumbers.provider(u, "cachedContentTokenCount")
        val candidates = UsageNumbers.provider(u, "candidatesTokenCount")
        val thoughts = UsageNumbers.provider(u, "thoughtsTokenCount")
        return UsageChunk(
            input = prompt?.let { (it - (cached ?: 0)).coerceAtLeast(0) },
            cacheRead = cached,
            cacheWrite = null,
            output = if (candidates == null && thoughts == null) null else (candidates ?: 0) + (thoughts ?: 0),
            reasoning = thoughts,
            cacheKeyPresent = cached != null,
            contextTokens = prompt,
        )
    }

    private fun maxPresent(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()
}

object UsageChunkMerger {

    fun merge(state: UsageChunk?, c: UsageChunk): UsageChunk {
        if (state == null) return c
        val newly = c.cacheRead != null && c.cacheRead > (state.cacheRead ?: 0)
        return UsageChunk(
            input = when {
                c.input == null -> state.input
                newly -> c.input
                else -> maxOf(state.input ?: 0, c.input)
            },
            cacheRead = maxField(state.cacheRead, c.cacheRead),
            cacheWrite = maxField(state.cacheWrite, c.cacheWrite),
            output = maxField(state.output, c.output),
            reasoning = maxField(state.reasoning, c.reasoning),
            cacheKeyPresent = state.cacheKeyPresent || c.cacheKeyPresent,
            contextTokens = c.contextTokens,
        )
    }

    fun merge(state: LLMUsage?, next: LLMUsage): LLMUsage {
        val nextChunk = next.chunk ?: return next
        val stateChunk = state?.chunk ?: return next
        return merge(stateChunk, nextChunk).toLLMUsage()
    }

    private fun maxField(a: Long?, b: Long?): Long? = if (b == null) a else maxOf(a ?: 0, b)
}
