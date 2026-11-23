package com.openminis.app.provider.gemini

import com.openminis.app.provider.thinking.ThinkingRuleResolver.GeminiThinkingFormat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object GeminiThinkingRetry {

    private val preferred = ConcurrentHashMap<String, GeminiThinkingFormat>()

    private fun key(instance: String, modelId: String) = "$instance\u0000$modelId"

    fun preferred(instance: String, modelId: String): GeminiThinkingFormat? =
        preferred[key(instance, modelId)]

    fun remember(instance: String, modelId: String, format: GeminiThinkingFormat) {
        preferred[key(instance, modelId)] = format
    }

    internal fun clear() = preferred.clear()

    fun formatFor(errorBody: String): GeminiThinkingFormat? {
        val text = errorBody.lowercase()
        return when {
            "thinking_level_minimal" in text || "minimal is not supported" in text ->
                GeminiThinkingFormat.LEVEL_NO_MINIMAL
            "thinking budget" in text &&
                ("not supported" in text || "use thinking level" in text || "deprecated" in text) ->
                GeminiThinkingFormat.LEVEL
            "thinking level" in text && "not supported" in text -> GeminiThinkingFormat.BUDGET
            else -> null
        }
    }

    fun thinkingConfigOf(body: JSONObject): String? =
        body.optJSONObject("generationConfig")?.optJSONObject("thinkingConfig")?.toString()

    fun describe(config: String?): String {
        val c = config?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return "none"
        return when {
            c.has("thinkingLevel") -> "level=${c.optString("thinkingLevel")}"
            c.has("thinkingBudget") -> "budget=${c.optInt("thinkingBudget")}"
            else -> "empty"
        }
    }
}
