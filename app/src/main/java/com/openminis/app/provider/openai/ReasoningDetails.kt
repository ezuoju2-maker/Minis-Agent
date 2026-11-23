package com.openminis.app.provider.openai

import org.json.JSONObject

internal object ReasoningDetails {

    data class Parsed(
        val text: String,
        val encryptedCount: Int,
        val sawReasoning: Boolean,
    )

    private val STRING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

    fun parse(delta: JSONObject): Parsed? {
        val details = delta.optJSONArray("reasoning_details") ?: return null
        val chunkHadStringReasoning = STRING_FIELDS.any {
            (delta.opt(it) as? String).orEmpty().isNotEmpty()
        }
        if (chunkHadStringReasoning) return null

        val text = StringBuilder()
        var encrypted = 0
        var saw = false
        for (i in 0 until details.length()) {
            val item = details.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "reasoning.text" -> { saw = true; text.append(stringOrEmpty(item, "text")) }
                "reasoning.summary" -> { saw = true; text.append(stringOrEmpty(item, "summary")) }
                "reasoning.encrypted" -> { saw = true; encrypted++ }
            }
        }
        if (!saw) return null
        return Parsed(text.toString(), encrypted, saw)
    }

    private fun stringOrEmpty(o: JSONObject, key: String): String = (o.opt(key) as? String).orEmpty()
}
