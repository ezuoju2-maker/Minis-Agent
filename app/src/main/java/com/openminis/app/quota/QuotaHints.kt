package com.openminis.app.quota

import java.math.BigDecimal
import java.util.Locale



object QuotaPickerHintRules {
    data class ModelSpecificHint(val status: QuotaStatus, val detail: String?, val isManual: Boolean)

    fun modelRow(v: ModelQuotaView): ModelSpecificHint? {
        val d = v.deciding ?: return null
        if (!v.effective.isWarning || d.isShared) return null
        return ModelSpecificHint(d.status.status, detail(d.status), d.isManual)
    }

    data class MemberPill(
        val text: String,
        val tone: QuotaTone,
        val poolKey: QuotaPoolKey,
        val isModelSpecific: Boolean,
        val isManual: Boolean,
        val isPercent: Boolean,
        val level: QuotaLevel = QuotaLevel.normal,
    )

    fun memberPill(
        v: ModelQuotaView,
        pools: Map<QuotaPoolKey, QuotaPool>,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): MemberPill? {
        fun usable(b: ModelQuotaView.BoundPool) =
            b.status.status != QuotaStatus.unknown && b.status.freshness != QuotaFreshness.expired &&
                pools[b.poolKey]?.let { it.kind != QuotaPool.Kind.rateLimit } == true
        val known = QuotaHeadline.withoutSpentCurrencyBalances(
            v.bound.filter(::usable), pool = { pools[it.poolKey] }, status = { it.status.status })
        val chosen = known.filter { !it.isShared }.maxWithOrNull(QuotaModelResolver.boundComparator)
            ?: known.filter { it.isShared }.maxWithOrNull(QuotaModelResolver.boundComparator)
            ?: return null
        val pool = pools[chosen.poolKey] ?: return null
        val status = chosen.status
        val u = status.utilization
        val remaining = pool.amounts?.remaining
        val total = pool.amounts?.total
        val used = pool.amounts?.used
        val (text, isPercent) = when {
            u != null -> QuotaFormat.percentUsedText(u.percent, strings) to true
            remaining != null -> QuotaFormat.amount(remaining, pool.unit, locale, strings) to false
            total != null && used != null -> QuotaFormat.amount(total.subtract(used), pool.unit, locale, strings) to false
            else -> return null
        }
        return MemberPill(
            text = text,
            tone = QuotaToneRules.pillTone(status),
            poolKey = chosen.poolKey,
            isModelSpecific = !chosen.isShared,
            isManual = chosen.isManual,
            isPercent = isPercent,
            level = QuotaLevelRules.level(v, pools),
        )
    }

    fun headerText(
        pool: QuotaPool,
        status: PoolStatus,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): String? {
        if (status.status == QuotaStatus.unknown || status.freshness == QuotaFreshness.expired) return null
        return QuotaFormat.shortText(pool, status, locale, strings)
    }

    private fun detail(s: PoolStatus): String? {
        val u = s.utilization ?: return null
        return s.decidingWindowId?.let { "$it " }.orEmpty() + QuotaFormat.percent(u.percent)
    }
}


object QuotaComposerHintRules {
    data class Segment(
        val text: String,
        val highlight: String,
        val level: QuotaLevel,
    )

    fun segment(
        v: ModelQuotaView,
        pools: Map<QuotaPoolKey, QuotaPool>,
        now: Long = System.currentTimeMillis(),
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): Segment? {
        val pill = QuotaPickerHintRules.memberPill(v, pools, locale, strings) ?: return null
        val pool = pools[pill.poolKey]
        if (pill.isPercent && pool != null) {
            windowsFigure(pool, now, freshness)?.let { windows ->
                return Segment("${strings.quota()} ${strings.used(windows)}", windows, pill.level)
            }
        }
        val text = when {
            pill.isPercent -> "${strings.quota()} ${pill.text}"
            pool?.kind == QuotaPool.Kind.prepaidBalance -> strings.balance(pill.text)
            else -> strings.left(pill.text)
        }
        return Segment(text, pill.text, pill.level)
    }

    fun windowsFigure(pool: QuotaPool, now: Long, freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy()): String? {
        val rated: List<Pair<QuotaWindow, BigDecimal>> = pool.windows.mapNotNull { w ->
            if (freshness.freshness(w, now) == QuotaFreshness.expired) return@mapNotNull null
            val u = w.utilization(pool.kind, pool.unlimited) ?: return@mapNotNull null
            w to u.percent
        }
        if (rated.size < 2) return null
        return rated.sortedWith(
            compareBy<Pair<QuotaWindow, BigDecimal>> { it.first.durationSeconds ?: Long.MAX_VALUE }
                .thenBy { it.first.id },
        ).joinToString(" / ") { "${it.first.id} ${QuotaFormat.percent(it.second)}" }
    }
}
