package com.openminis.app.ui.chat

internal object DebugUsageHint {
    data class Quota(val text: String, val highlight: String?, val tier: ContextUsage.Tier)

    val SAMPLE_QUOTA = Quota("Quota 5h 14% / 7d 32% used", "14%", ContextUsage.Tier.NORMAL)

    fun build(generation: Int, percent: Int, windowTokens: Int, quota: Quota?): ContextUsageHint {
        val window = windowTokens.coerceAtLeast(1)
        val used = (window.toLong() * percent.coerceIn(1, 100) / 100).toInt().coerceAtLeast(1)
        val usage = ContextUsage(used, window)
        return ContextUsageHint(
            generation = generation,
            text = "",
            highlights = listOf("${usage.percent}%", TokenCountFormatter.sizePair(usage)),
            tier = usage.tier,
            quotaText = quota?.text,
            quotaHighlight = quota?.highlight,
            quotaTier = quota?.tier ?: ContextUsage.Tier.NORMAL,
        )
    }

    fun tierOf(name: String?): ContextUsage.Tier = when (name?.lowercase()) {
        "warning", "low" -> ContextUsage.Tier.WARNING
        "critical", "depleted" -> ContextUsage.Tier.CRITICAL
        else -> ContextUsage.Tier.NORMAL
    }
}
