package com.openminis.app.ui.chat


internal data class ContextUsage(
    val usedTokens: Int,
    val windowTokens: Int,
) {
    val fraction: Double
        get() = if (windowTokens > 0) usedTokens.toDouble() / windowTokens.toDouble() else 0.0

    val percent: Int
        get() = Math.round(fraction * 100).toInt()

    val tier: Tier
        get() = when {
            fraction >= CRITICAL_FRACTION -> Tier.CRITICAL
            fraction >= WARNING_FRACTION -> Tier.WARNING
            else -> Tier.NORMAL
        }

    internal enum class Tier(val rank: Int) {
        NORMAL(0),

        WARNING(1),

        CRITICAL(2),
    }

    companion object {
        const val WARNING_FRACTION = 0.70
        const val CRITICAL_FRACTION = 0.80

        fun from(usedTokens: Int, windowTokens: Int?): ContextUsage? {
            if (usedTokens <= 0) return null
            if (windowTokens == null || windowTokens <= 0) return null
            return ContextUsage(usedTokens, windowTokens)
        }
    }
}

internal data class ContextUsageHint(
    val generation: Int,
    val text: String,
    val highlights: List<String>,
    val tier: ContextUsage.Tier,
    val quotaText: String? = null,
    val quotaHighlight: String? = null,
    val quotaTier: ContextUsage.Tier = ContextUsage.Tier.NORMAL,
)

internal object TokenCountFormatter {
    fun short(count: Int): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        return if (k % 1.0 == 0.0) "${k.toInt()}k"
        else String.format(java.util.Locale.US, "%.1fk", k)
    }

    fun sizePair(usage: ContextUsage): String =
        "${short(usage.usedTokens)} / ${short(usage.windowTokens)}"
}

internal class ContextTierCrossingTracker {

    var lastTier: ContextUsage.Tier = ContextUsage.Tier.NORMAL
        private set

    var lastFiredAtMs: Long? = null
        private set

    var lastFiredTier: ContextUsage.Tier? = null
        private set

    fun reset(tier: ContextUsage.Tier = ContextUsage.Tier.NORMAL) {
        lastTier = tier
        lastFiredAtMs = null
        lastFiredTier = null
    }

    fun observe(tier: ContextUsage.Tier, nowMs: Long): Boolean {
        val previous = lastTier
        lastTier = tier
        if (tier.rank <= previous.rank) return false
        val firedAt = lastFiredAtMs
        if (firedAt != null &&
            nowMs - firedAt < MIN_INTERVAL_MS &&
            tier.rank <= (lastFiredTier?.rank ?: -1)
        ) {
            return false
        }
        return true
    }

    fun markFired(tier: ContextUsage.Tier, nowMs: Long) {
        lastFiredAtMs = nowMs
        lastFiredTier = tier
    }

    fun recentlyFired(tier: ContextUsage.Tier, nowMs: Long): Boolean {
        val firedAt = lastFiredAtMs ?: return false
        if (lastFiredTier != tier) return false
        return nowMs - firedAt < MIN_INTERVAL_MS
    }

    companion object {
        const val MIN_INTERVAL_MS = 4_000L
    }
}

internal class ContextUsageHintLifecycle {

    var hint: ContextUsageHint? = null
        private set

    var hasProtectedFirstFocus: Boolean = false
        private set

    private var lastAcceptedGeneration: Int = -1

    val isShowing: Boolean get() = hint != null

    fun present(candidate: ContextUsageHint): Boolean {
        if (candidate.generation <= lastAcceptedGeneration) return false
        lastAcceptedGeneration = candidate.generation
        hint = candidate
        hasProtectedFirstFocus = false
        return true
    }

    fun onFocusGained(): Boolean {
        if (hint == null) return true
        if (!hasProtectedFirstFocus) {
            hasProtectedFirstFocus = true
            return false
        }
        retire()
        return true
    }

    fun onImeShown(): Boolean = onFocusGained()

    fun onTextChanged(text: String) {
        if (text.isNotEmpty()) retire()
    }

    fun retire() {
        hint = null
        hasProtectedFirstFocus = false
    }
}
