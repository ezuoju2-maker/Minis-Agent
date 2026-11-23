package com.openminis.app.usage

import com.openminis.app.data.model.LLMUsage
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

object UsageNumbers {
    const val MAX_COUNT = 9_007_199_254_740_991L

    fun count(v: Any?): Long? = when (v) {
        is Int, is Long, is Short, is Byte -> (v as Number).toLong().let { if (it < 0 || it > MAX_COUNT) 0 else it }
        is BigInteger -> if (v.signum() < 0 || v > BigInteger.valueOf(MAX_COUNT)) 0 else v.toLong()
        is BigDecimal -> if (v.signum() < 0 || v > BigDecimal.valueOf(MAX_COUNT)) 0 else v.toLong()
        is Double, is Float -> (v as Number).toDouble().let { d ->
            if (d.isNaN() || d < 0 || d > MAX_COUNT.toDouble()) 0 else d.toLong()
        }
        else -> null
    }

    fun provider(o: JSONObject, key: String): Long? = if (o.has(key)) count(o.opt(key)) else null

    fun stored(o: JSONObject, vararg keys: String): Long {
        for (k in keys) if (o.has(k)) return count(o.opt(k)) ?: 0
        return 0
    }
}

data class StoredUsage(
    val input: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val output: Long,
    val reasoning: Long?,
    val cacheReported: Boolean?,
    val copied: Boolean,
) {
    val total: Long get() = input + cacheRead + cacheWrite + output
}

sealed class StoredUsageParse {
    data object NoUsage : StoredUsageParse()
    data object Invalid : StoredUsageParse()
    data class Valid(val usage: StoredUsage) : StoredUsageParse()
}

object StoredUsageJson {

    fun parse(text: String?): StoredUsageParse {
        if (text.isNullOrBlank()) return StoredUsageParse.NoUsage
        val o = try { JSONObject(text) } catch (_: JSONException) { return StoredUsageParse.Invalid }
        return StoredUsageParse.Valid(fromObject(o))
    }

    fun fromObject(o: JSONObject): StoredUsage {
        val output = UsageNumbers.stored(o, "outputTokens")
        return StoredUsage(
            input = UsageNumbers.stored(o, "inputTokens"),
            cacheRead = UsageNumbers.stored(o, "cacheReadTokens", "cacheReadInputTokens"),
            cacheWrite = UsageNumbers.stored(o, "cacheCreationTokens", "cacheCreationInputTokens"),
            output = output,
            reasoning = if (o.has("reasoningTokens")) minOf(UsageNumbers.count(o.opt("reasoningTokens")) ?: 0, output) else null,
            cacheReported = o.opt("cacheReported") as? Boolean,
            copied = o.opt("copied") == true,
        )
    }

    fun extraFields(usage: LLMUsage): String = buildString {
        usage.reasoningTokens?.let { append(",\"reasoningTokens\":").append(it) }
        usage.cacheReported?.let { append(",\"cacheReported\":").append(it) }
    }

    fun markCopied(usage: String?): String? {
        if (usage.isNullOrBlank()) return usage
        val o = try { JSONObject(usage) } catch (_: JSONException) { return usage }
        if (o.opt("copied") == true) return usage
        return o.put("copied", true).toString()
    }
}
