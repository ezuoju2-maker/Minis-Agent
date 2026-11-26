package com.openminis.app.clientfrail

import java.util.Locale

/**
 * 按 provider 归一化拒答类别。
 *
 * 各家报回来的拒答表面差别很大：Anthropic 给 stop_reason=refusal 加
 * stop_details.category；OpenAI 给 finish_reason=content_filter 或
 * response.incomplete.reason；Gemini 给 promptFeedback.blockReason 加
 * safetyRatings。TurnStateMachine 的分类器原本只做英文子串匹配，
 * "MAX_TOKENS"、"MODEL_OTHER"、"PROHIBITED_CONTENT" 这类取值会落进
 * DIRECT_FRAMING 这个兜底分支，恢复轮就选错了族。这里先把 provider 的
 * 原始取值折成状态机认得的类别名。
 */
internal object ProviderRule {

    fun classify(
        providerFamily: String?,
        surface: String?,
        category: String?,
        explanation: String?,
    ): String? {
        val fam = providerFamily?.lowercase(Locale.ROOT) ?: ""
        val cat = category?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_') ?: ""
        val blob = ((surface ?: "") + " " + cat + " " + (explanation ?: ""))
            .lowercase(Locale.ROOT)

        // 1) provider 明确给了类别：按家族映射
        if (cat.isNotEmpty()) {
            when (fam) {
                "anthropic" -> return when {
                    cat.contains("harassment") || cat.contains("hate") ||
                        cat.contains("sexual") || cat.contains("violence") ||
                        cat.contains("danger") -> "CLASSIFIER_GENERIC"
                    cat.contains("token") || cat.contains("length") -> "MISSING_SPEC"
                    else -> "CLASSIFIER_GENERIC"
                }
                "gemini" -> return when {
                    cat.contains("recitation") -> "MISSING_SPEC"
                    cat.contains("other") || cat.contains("blocklist") ||
                        cat.contains("prohibited") || cat.contains("spii") -> "KEYWORD"
                    else -> "CLASSIFIER_GENERIC"
                }
                "openai", "xai", "perplexity", "cohere", "mistral",
                "meta", "alibaba", "deepseek", "qwen", "moonshot",
                "minimax", "zhipu", "xiaomi", "generic", "qoder" -> return when {
                    cat.contains("safety") || cat.contains("harm") ||
                        cat.contains("sexual") || cat.contains("harass") ||
                        cat.contains("hate") -> "CLASSIFIER_GENERIC"
                    cat.contains("other") || cat.contains("blocklist") ||
                        cat.contains("prohibited") || cat.contains("spii") -> "KEYWORD"
                    cat.contains("length") || cat.contains("token") ||
                        cat.contains("invalid") || cat.contains("malformed") ||
                        cat.contains("unsupported") -> "MISSING_SPEC"
                    else -> null
                }
            }
        }

        // 2) 只有表面串：靠家族特征收窄
        return when {
            fam == "gemini" && blob.contains("max_tokens") -> "MISSING_SPEC"
            fam == "gemini" && blob.contains("safety") -> "CLASSIFIER_GENERIC"
            fam == "anthropic" && blob.contains("length") -> "MISSING_SPEC"
            blob.contains("content_filter") || blob.contains("recitation") ||
                blob.contains("stop_sequence") -> "STREAM_ABORT"
            else -> null
        }
    }
}
