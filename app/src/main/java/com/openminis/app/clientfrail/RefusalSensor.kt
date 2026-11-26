package com.openminis.app.clientfrail

import com.openminis.app.provider.StopReasons
import java.util.Locale

data class RefusalReading(
    val refused: Boolean,
    val surface: String = "",
    val category: String? = null,
    val explanation: String? = null,
    val partialText: String? = null,
    /** provider 已判明的拒答类别名（与 TurnStateMachine.RefusalClass 同名，大小写不敏感） */
    val refusalClass: String? = null,
) {
    companion object {
        val PASS = RefusalReading(refused = false)
    }
}

object RefusalSensor {

    private const val MIN_PARTIAL_CHARS = 24

    private val OPENAI_FINITE_REASONS: Set<String> = setOf(
        "content_filter", "refusal", "safety", "blocked", "moderation",
    )

    private val GEMINI_BLOCK_REASONS: Set<String> = setOf(
        "SAFETY", "BLOCKED", "PROHIBITED_CONTENT", "OTHER",
        "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT",
        "NO_IMAGE", "RECITATION", "MALFORMED_FUNCTION_CALL",
    )

    private val GEMINI_HARM_CATEGORIES: Set<String> = setOf(
        "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT",
        "HARM_CATEGORY_CIVIC_INTEGRITY",
    )

    private val GEMINI_FINISH_BLOCKED: Set<String> = setOf(
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
    )

    fun fromStopReason(reason: String?): RefusalReading {
        if (reason == null) return RefusalReading.PASS
        val r = reason.lowercase(Locale.ROOT)
        return when {
            r in OPENAI_FINITE_REASONS -> RefusalReading(
                refused = true, surface = "stop_reason:$reason",
            )
            GEMINI_FINISH_BLOCKED.any { it.equals(reason, ignoreCase = true) } ->
                RefusalReading(refused = true, surface = "finish_reason:$reason")
            else -> RefusalReading.PASS
        }
    }

    fun geminiPromptFeedback(json: org.json.JSONObject?): RefusalReading {
        val fb = json?.optJSONObject("promptFeedback") ?: return RefusalReading.PASS
        val block = fb.optString("blockReason", "")
        if (block.isBlank() || block.equals("BLOCK_REASON_UNSPECIFIED", ignoreCase = true)) {
            return RefusalReading.PASS
        }
        if (GEMINI_BLOCK_REASONS.none { it.equals(block, ignoreCase = true) }) {
            return RefusalReading.PASS
        }
        val categories = ArrayList<String>()
        val arr = fb.optJSONArray("safetyRatings")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val cat = it.optString("category", "")
                val prob = it.optString("probability", "")
                val blocked = it.optBoolean("blocked", false)
                if (blocked || prob.equals("HIGH", ignoreCase = true)) {
                    if (cat.isNotEmpty()) categories.add(cat)
                }
            }
        }
        return RefusalReading(
            refused = true,
            surface = "gemini.promptFeedback:$block",
            category = categories.takeIf { it.isNotEmpty() }?.joinToString(","),
            explanation = fb.optString("blockReasonMessage", "").takeIf { it.isNotBlank() },
        )
    }

    fun geminiCandidate(candidate: org.json.JSONObject?): RefusalReading {
        if (candidate == null) return RefusalReading.PASS
        val finish = candidate.optString("finishReason", "")
        if (finish.isNotBlank() && GEMINI_FINISH_BLOCKED.any { it.equals(finish, ignoreCase = true) }) {
            return RefusalReading(refused = true, surface = "gemini.candidate:$finish")
        }
        val ratings = candidate.optJSONArray("safetyRatings") ?: return RefusalReading.PASS
        val blocked = ArrayList<String>()
        for (i in 0 until ratings.length()) {
            val it = ratings.optJSONObject(i) ?: continue
            val cat = it.optString("category", "")
            val isBlocked = it.optBoolean("blocked", false) ||
                it.optString("probability", "").equals("HIGH", ignoreCase = true)
            if (isBlocked && cat.isNotEmpty() &&
                GEMINI_HARM_CATEGORIES.any { it.equals(cat, ignoreCase = true) }
            ) {
                blocked.add(cat.removePrefix("HARM_CATEGORY_"))
            }
        }
        if (blocked.isEmpty()) return RefusalReading.PASS
        return RefusalReading(
            refused = true,
            surface = "gemini.safetyRatings",
            category = blocked.joinToString(","),
        )
    }

    fun anthropicStop(json: org.json.JSONObject?): RefusalReading {
        val reason = json?.optString("stop_reason", "") ?: ""
        if (!StopReasons.isRefusal(reason)) return RefusalReading.PASS
        val details = json.optJSONObject("stop_details")
        return RefusalReading(
            refused = true,
            surface = "anthropic.stop_reason:$reason",
            category = details?.optString("category", "")?.takeIf { it.isNotBlank() },
            explanation = details?.optString("explanation", "")?.takeIf { it.isNotBlank() },
        )
    }

    fun openAIStreamReason(reason: String?): RefusalReading {
        if (reason == null) return RefusalReading.PASS
        val r = reason.lowercase(Locale.ROOT)
        if (r != "content_filter" && r != "refusal") return RefusalReading.PASS
        return RefusalReading(refused = true, surface = "openai.finish_reason:$reason")
    }

    fun openAIResponseIncomplete(details: org.json.JSONObject?): RefusalReading {
        val reason = details?.optString("reason", "") ?: return RefusalReading.PASS
        val r = reason.lowercase(Locale.ROOT)
        return if (r in OPENAI_FINITE_REASONS) {
            RefusalReading(refused = true, surface = "openai.incomplete:$reason")
        } else {
            RefusalReading.PASS
        }
    }

    fun hasUsablePartial(text: String?): Boolean =
        text != null && text.trim().length >= MIN_PARTIAL_CHARS

    fun merge(vararg readings: RefusalReading): RefusalReading {
        for (r in readings) if (r.refused) return r
        return RefusalReading.PASS
    }

    fun describe(r: RefusalReading): String = buildString {
        append("refused=").append(r.refused)
        if (r.surface.isNotEmpty()) append(" surface=").append(r.surface)
        if (r.category != null) append(" category=").append(r.category)
        if (r.explanation != null) append(" explanationLen=").append(r.explanation.length)
    }
}
