package com.openminis.app.data.model

internal fun inferContextWindowTokens(model: LLMModel): Int {
    model.contextWindow?.takeIf { it > 0 }?.let { return it }
    val idLower = model.id.lowercase()

    val millionClassPatterns = listOf(
        Regex("claude-.*-1m"),
        Regex("claude-opus-4-[5678]"),
        Regex("claude-sonnet-4-[567]"),
    )
    if (millionClassPatterns.any { it.containsMatchIn(idLower) }) return 1_000_000
    if (idLower.contains("gemini-2.5-pro") ||
        idLower.contains("gemini-2.0-pro") ||
        idLower.contains("gemini-1.5-pro") ||
        idLower.contains("gemini-2.5-flash") ||
        idLower.contains("gemini-3-pro") ||
        idLower.contains("gemini-3-flash")
    ) return 1_000_000

    if (idLower.startsWith("claude-") || idLower.contains("/claude-")) return 200_000

    if (idLower.startsWith("gpt-4o") || idLower.contains("/gpt-4o")) return 128_000
    if (idLower.startsWith("gpt-4-turbo") || idLower.contains("/gpt-4-turbo")) return 128_000
    if (idLower.startsWith("gpt-5") || idLower.contains("/gpt-5")) return 400_000

    if (idLower.startsWith("gpt-4") || idLower.contains("/gpt-4")) return 8_000
    if (idLower.startsWith("gpt-3.5") || idLower.contains("/gpt-3.5")) return 16_000

    if (idLower.startsWith("o3") || idLower.contains("/o3")) return 200_000
    if (idLower.startsWith("o4") || idLower.contains("/o4")) return 200_000
    if (idLower.startsWith("codex") || idLower.contains("/codex")) return 200_000

    if (idLower.startsWith("deepseek-") || idLower.contains("/deepseek-")) return 128_000

    if (idLower.startsWith("grok") || idLower.contains("/grok")) {
        if (idLower.contains("grok-2") || idLower.contains("grok-3")) return 131_072
        return 256_000
    }

    return 128_000
}
