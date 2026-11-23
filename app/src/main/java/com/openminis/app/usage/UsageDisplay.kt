package com.openminis.app.usage

import java.util.Locale
import kotlin.math.roundToLong

object UsageDisplay {

    const val TOP_N = 8

    fun tokens(n: Long): String {
        if (n < 1_000) return n.coerceAtLeast(0).toString()
        val (value, unit) = when {
            n < 999_950 -> n / 1_000.0 to "k"
            n < 999_950_000 -> n / 1_000_000.0 to "M"
            else -> n / 1_000_000_000.0 to "B"
        }
        val tenths = (value * 10).roundToLong()
        val text = if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.ROOT, "%.1f", tenths / 10.0)
        return text + unit
    }

    fun percent(ratio: Double?): String = when {
        ratio == null || ratio.isNaN() -> "—"
        ratio > 0 && ratio < 0.005 -> "<1%"
        else -> "${(ratio * 100).roundToLong()}%"
    }

    fun share(part: Long, total: Long): Double? = if (total > 0) part.toDouble() / total else null

    fun isUnknown(g: UsageGroup): Boolean =
        g.key == UsageReportBuilder.UNKNOWN_PROVIDER || g.key == UsageReportBuilder.UNKNOWN_MODEL

    data class Folded(
        val top: List<UsageGroup>,
        val rest: List<UsageGroup>,
        val unknown: UsageGroup?,
    ) {
        val restTotal: Long get() = rest.sumOf { it.tokens.total }
    }

    fun fold(groups: List<UsageGroup>, limit: Int = TOP_N): Folded {
        val known = groups.filterNot(::isUnknown)
        val cut = if (known.size == limit + 1) known.size else limit
        return Folded(known.take(cut), known.drop(cut), groups.firstOrNull(::isUnknown))
    }
}
