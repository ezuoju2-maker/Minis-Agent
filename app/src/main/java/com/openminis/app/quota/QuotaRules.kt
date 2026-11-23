package com.openminis.app.quota

import java.math.BigDecimal
import java.math.MathContext


private val HUNDRED = BigDecimal(100)
private val MINUS_ONE = BigDecimal(-1)

internal fun BigDecimal.percentOf(total: BigDecimal): BigDecimal =
    divide(total, MathContext.DECIMAL128).multiply(HUNDRED)


fun QuotaAmounts.computedUtilization(kind: QuotaPool.Kind, unlimited: Boolean): QuotaUtilization? {
    if (unlimited) return null
    when (kind) {
        QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance, QuotaPool.Kind.usageWindow -> Unit
        QuotaPool.Kind.prepaidBalance, QuotaPool.Kind.rateLimit -> return null
    }
    val total = total ?: return null
    if (total.signum() <= 0) return null
    val used = used ?: remaining?.let { total.subtract(it) } ?: return null
    return QuotaUtilization(used.percentOf(total), QuotaUtilization.Basis.computed)
}

fun QuotaWindow.utilization(poolKind: QuotaPool.Kind, unlimited: Boolean): QuotaUtilization? {
    if (unlimited) return null
    if (poolKind == QuotaPool.Kind.prepaidBalance) return null
    reportedUsedFraction?.let { return QuotaUtilization(it.multiply(HUNDRED), QuotaUtilization.Basis.reported) }
    reportedRemainingFraction?.let {
        return QuotaUtilization(BigDecimal.ONE.subtract(it).multiply(HUNDRED), QuotaUtilization.Basis.reportedComplement)
    }
    return amounts?.computedUtilization(poolKind, unlimited)
}


data class QuotaFreshnessPolicy(
    val activeFreshMs: Long = 15 * 60_000L,
    val activeAmountsStaleMs: Long = 6 * 3_600_000L,
    val activeWindowStaleMs: Long = 3_600_000L,
) {
    fun freshnessOfAmounts(pool: QuotaPool, now: Long): QuotaFreshness {
        val age = now - pool.observedAt
        if (age < activeFreshMs) return QuotaFreshness.fresh
        return if (age <= activeAmountsStaleMs) QuotaFreshness.stale else QuotaFreshness.expired
    }

    fun freshness(window: QuotaWindow, now: Long): QuotaFreshness {
        window.resetsAt?.let { if (it <= now) return QuotaFreshness.expired }
        val age = now - window.observedAt
        if (age < activeFreshMs) return QuotaFreshness.fresh
        return if (age <= activeWindowStaleMs) QuotaFreshness.stale else QuotaFreshness.expired
    }

    fun freshness(pool: QuotaPool, now: Long): QuotaFreshness {
        val parts = mutableListOf<QuotaFreshness>()
        if (pool.amounts != null || pool.windows.isEmpty()) parts += freshnessOfAmounts(pool, now)
        pool.windows.mapTo(parts) { freshness(it, now) }
        if (QuotaFreshness.fresh in parts) return QuotaFreshness.fresh
        if (QuotaFreshness.stale in parts) return QuotaFreshness.stale
        return QuotaFreshness.expired
    }
}


object QuotaStatusRules {
    fun status(utilization: QuotaUtilization, thresholds: QuotaThresholds): QuotaStatus {
        if (utilization.percent >= thresholds.exhaustedPercent) return QuotaStatus.exhausted
        if (utilization.percent >= thresholds.lowPercent) return QuotaStatus.low
        return QuotaStatus.available
    }

    fun poolStatus(
        pool: QuotaPool,
        now: Long,
        policy: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): PoolStatus {
        val freshness = policy.freshness(pool, now)
        if (freshness == QuotaFreshness.expired) {
            return PoolStatus(QuotaStatus.unknown, null, null, QuotaFreshness.expired)
        }
        if (pool.verdict == QuotaPool.Verdict.rejected) {
            return PoolStatus(QuotaStatus.exhausted, null, null, freshness)
        }

        if (pool.kind == QuotaPool.Kind.rateLimit) {
            val limited = pool.windows.firstOrNull { w ->
                if (policy.freshness(w, now) == QuotaFreshness.expired) return@firstOrNull false
                val rem = w.amounts?.remaining
                rem != null && rem.signum() == 0 && (w.resetsAt?.let { it > now } ?: false)
            }
            return PoolStatus(
                if (limited != null) QuotaStatus.rateLimited else QuotaStatus.available,
                limited?.id, null, freshness,
            )
        }

        var bestStatus: QuotaStatus? = null
        var bestWindow: QuotaWindow? = null
        var bestUtil: QuotaUtilization? = null
        for (w in pool.windows) {
            if (policy.freshness(w, now) == QuotaFreshness.expired) continue
            val u = w.utilization(pool.kind, pool.unlimited)
            val remaining = w.amounts?.remaining
            val s = when {
                u != null -> status(u, thresholds)
                remaining != null -> if (remaining.signum() <= 0) QuotaStatus.exhausted else QuotaStatus.available
                else -> QuotaStatus.unknown
            }
            val current = bestStatus
            if (current == null) {
                bestStatus = s; bestWindow = w; bestUtil = u
            } else {
                val tighter = s > current ||
                    (s == current && (u?.percent ?: MINUS_ONE) > (bestUtil?.percent ?: MINUS_ONE))
                if (tighter) { bestStatus = s; bestWindow = w; bestUtil = u }
            }
        }

        var amountStatus = QuotaStatus.unknown
        var amountUtil: QuotaUtilization? = null
        val amounts = pool.amounts
        if (amounts != null) {
            when (pool.kind) {
                QuotaPool.Kind.prepaidBalance -> {
                    val remaining = amounts.remaining
                    if (remaining != null) {
                        val unit = pool.unit
                        val low = (unit as? QuotaUnit.Currency)
                            ?.let { thresholds.lowBalance[pool.subjectId]?.get(it.code.uppercase()) }
                        amountStatus = when {
                            remaining.signum() <= 0 -> QuotaStatus.exhausted
                            low != null && remaining < low -> QuotaStatus.low
                            else -> QuotaStatus.available
                        }
                    }
                }
                QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance, QuotaPool.Kind.usageWindow -> {
                    val u = amounts.computedUtilization(pool.kind, pool.unlimited)
                    val remaining = amounts.remaining
                    when {
                        pool.unlimited -> amountStatus = QuotaStatus.available
                        u != null -> { amountUtil = u; amountStatus = status(u, thresholds) }
                        remaining != null ->
                            amountStatus = if (remaining.signum() <= 0) QuotaStatus.exhausted else QuotaStatus.available
                    }
                }
                QuotaPool.Kind.rateLimit -> Unit
            }
        }

        var result = PoolStatus(amountStatus, null, amountUtil, freshness)
        val bs = bestStatus
        if (bs != null && bs >= result.status && bs != QuotaStatus.unknown) {
            result = PoolStatus(bs, bestWindow?.id, bestUtil, freshness)
        }
        if (pool.verdict == QuotaPool.Verdict.warning && result.status < QuotaStatus.low) {
            result = result.copy(status = QuotaStatus.low)
        }
        return result
    }
}


object QuotaModelKey {
    fun normalize(s: String): String =
        s.trim().lowercase().replace(" ", "-").replace("_", "-")

    fun matches(pattern: String, key: String): Boolean {
        val p = normalize(pattern)
        val k = normalize(key)
        val parts = p.split("*")
        if (parts.size <= 1) return p == k
        var rest = k
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            when (i) {
                0 -> {
                    if (!rest.startsWith(part)) return false
                    rest = rest.substring(part.length)
                }
                parts.size - 1 -> return rest.endsWith(part)
                else -> {
                    val idx = rest.indexOf(part)
                    if (idx < 0) return false
                    rest = rest.substring(idx + part.length)
                }
            }
        }
        return true
    }
}

object QuotaModelResolver {
    fun view(
        instanceId: String,
        modelId: String,
        subjectId: QuotaSubjectID?,
        pools: Map<QuotaPoolKey, QuotaPool>,
        bindings: List<QuotaPoolBinding>,
        manualBindings: List<QuotaPoolBinding> = emptyList(),
        now: Long,
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): ModelQuotaView {
        val modelKey = QuotaModelKey.normalize(modelId)
        if (subjectId == null) return ModelQuotaView.empty(instanceId, modelKey)

        val chosen = LinkedHashMap<QuotaPoolKey, QuotaPoolBinding>()
        fun consider(b: QuotaPoolBinding) {
            val pool = pools[b.poolKey] ?: return
            if (pool.subjectId != subjectId) return
            val applies = when (val t = b.target) {
                QuotaPoolBinding.Target.Subject -> true
                is QuotaPoolBinding.Target.Model -> QuotaModelKey.normalize(t.key) == modelKey
                is QuotaPoolBinding.Target.ModelFamily -> QuotaModelKey.matches(t.pattern, modelKey)
                is QuotaPoolBinding.Target.Product -> false
            }
            if (!applies) return
            val existing = chosen[b.poolKey]
            if (existing == null ||
                specificity(b) > specificity(existing) ||
                (specificity(b) == specificity(existing) && b.evidence.rank > existing.evidence.rank)
            ) {
                chosen[b.poolKey] = b
            }
        }
        bindings.forEach(::consider)
        manualBindings.forEach(::consider)

        val bound = chosen.values.mapNotNull { b ->
            val pool = pools[b.poolKey] ?: return@mapNotNull null
            ModelQuotaView.BoundPool(b.poolKey, b, QuotaStatusRules.poolStatus(pool, now, freshness, thresholds))
        }.sortedBy { it.poolKey.rawValue }

        val known = bound.filter { it.status.status != QuotaStatus.unknown }
        val deciding = known.maxWithOrNull(boundComparator)
        return ModelQuotaView(
            instanceId = instanceId,
            modelKey = modelKey,
            subjectId = subjectId,
            bound = bound,
            effective = deciding?.status?.status ?: QuotaStatus.unknown,
            deciding = deciding,
            isIncomplete = known.isNotEmpty() && known.size < bound.size,
            hasModelSpecificPool = bound.any { !it.isShared },
        )
    }

    internal val boundComparator = Comparator<ModelQuotaView.BoundPool> { a, b ->
        if (a.status.status != b.status.status) a.status.status.compareTo(b.status.status)
        else (a.status.utilization?.percent ?: MINUS_ONE).compareTo(b.status.utilization?.percent ?: MINUS_ONE)
    }

    private fun specificity(b: QuotaPoolBinding): Int = when (b.target) {
        QuotaPoolBinding.Target.Subject -> 0
        is QuotaPoolBinding.Target.ModelFamily -> 1
        is QuotaPoolBinding.Target.Product -> 1
        is QuotaPoolBinding.Target.Model -> 2
    }
}


object QuotaMerge {
    fun ingest(
        snapshot: UpstreamQuotaSnapshot,
        authoritative: Boolean,
        pools: MutableMap<QuotaPoolKey, QuotaPool>,
        bindings: MutableList<QuotaPoolBinding>,
    ) {
        if (authoritative) {
            val returned = snapshot.pools.map { it.id }.toSet()
            val retired = pools.values.filter {
                it.subjectId == snapshot.subjectId &&
                    it.provenance.adapterId == snapshot.provenance.adapterId &&
                    it.id !in returned
            }.map { it.id }.toSet()
            retired.forEach { pools.remove(it) }
            if (retired.isNotEmpty()) bindings.removeAll { it.poolKey in retired }
        }

        for (incoming in snapshot.pools) {
            val existing = pools[incoming.id]
            if (existing == null) {
                pools[incoming.id] = incoming
                continue
            }
            var merged = existing
            if (incoming.observedAt >= existing.observedAt) {
                merged = merged.copy(
                    kind = incoming.kind,
                    unit = incoming.unit,
                    vendorLabel = incoming.vendorLabel ?: existing.vendorLabel,
                    amounts = incoming.amounts,
                    verdict = incoming.verdict,
                    unlimited = incoming.unlimited,
                    provenance = incoming.provenance,
                )
            }
            val byId = LinkedHashMap<String, QuotaWindow>()
            existing.windows.forEach { byId.putIfAbsent(it.id, it) }
            for (w in incoming.windows) {
                val old = byId[w.id]
                if (old != null && old.observedAt > w.observedAt) continue
                byId[w.id] = w
            }
            val windows = existing.windows.mapNotNull { byId.remove(it.id) } +
                incoming.windows.mapNotNull { byId.remove(it.id) }
            pools[incoming.id] = merged.copy(
                windows = windows,
                observedAt = maxOf(existing.observedAt, incoming.observedAt),
            )
        }

        for (b in snapshot.bindings) {
            if (b.evidence == QuotaPoolBinding.Evidence.userManualMapping) continue
            val i = bindings.indexOfFirst { it.poolKey == b.poolKey && it.target == b.target }
            if (i >= 0) {
                if (b.evidence.rank > bindings[i].evidence.rank) bindings[i] = b
            } else {
                bindings += b
            }
        }
    }
}


data class QuotaRefreshPolicy(
    val normalIntervalMs: Long = 10 * 60_000L,
    val forcedMinIntervalMs: Long = 30_000L,
    val unattendedIntervalMs: Long = 3_600_000L,
    val backoffMs: List<Long> = listOf(300_000L, 600_000L, 1_200_000L, 1_800_000L, 3_600_000L),
    val capabilityProbeIntervalMs: Long = 24 * 3_600_000L,
) {
    companion object {
        fun forVendor(vendor: QuotaVendor): QuotaRefreshPolicy = when (vendor) {
            QuotaVendor.claudeSubscription, QuotaVendor.codexSubscription ->
                QuotaRefreshPolicy(normalIntervalMs = SUBSCRIPTION_FRESH_WINDOW_MS)
            else -> QuotaRefreshPolicy()
        }

        const val SUBSCRIPTION_FRESH_WINDOW_MS = 5 * 60_000L
    }
}

sealed class QuotaRefreshDecision {
    data object Allowed : QuotaRefreshDecision()

    data class ServerCooldown(val until: Long) : QuotaRefreshDecision()

    data class TooSoon(val until: Long) : QuotaRefreshDecision()

    data class Backoff(val until: Long) : QuotaRefreshDecision()

    data class ForcedDebounce(val until: Long) : QuotaRefreshDecision()

    data object CredentialRejected : QuotaRefreshDecision()

    val isAllowed: Boolean get() = this == Allowed
}

object QuotaRefreshGate {
    fun decide(
        record: QuotaRefreshRecord?,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
        attended: Boolean = true,
    ): QuotaRefreshDecision {
        if (record == null) return QuotaRefreshDecision.Allowed
        record.serverCooldownUntil?.let { if (it > now) return QuotaRefreshDecision.ServerCooldown(it) }
        if (forced) {
            val lastForced = record.lastForcedAt ?: return QuotaRefreshDecision.Allowed
            val until = lastForced + policy.forcedMinIntervalMs
            return if (until > now) QuotaRefreshDecision.ForcedDebounce(until) else QuotaRefreshDecision.Allowed
        }
        val kind = record.error?.kind
        if (kind == QuotaError.Kind.unauthorized || kind == QuotaError.Kind.forbidden) {
            return QuotaRefreshDecision.CredentialRejected
        }
        record.nextAllowedAt?.let { next ->
            if (next > now) {
                return if (record.error != null) QuotaRefreshDecision.Backoff(next) else QuotaRefreshDecision.TooSoon(next)
            }
        }
        if (!attended) {
            val last = maxOf(record.succeededAt ?: Long.MIN_VALUE, record.attemptedAt)
            val until = last + policy.unattendedIntervalMs
            if (until > now) return QuotaRefreshDecision.TooSoon(until)
        }
        return QuotaRefreshDecision.Allowed
    }

    fun recordSuccess(
        record: QuotaRefreshRecord?,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord = (record ?: QuotaRefreshRecord(attemptedAt = now)).copy(
        attemptedAt = now,
        succeededAt = now,
        error = null,
        consecutiveFailures = 0,
        serverCooldownUntil = null,
        nextAllowedAt = now + policy.normalIntervalMs,
        lastForcedAt = if (forced) now else record?.lastForcedAt,
    )

    fun recordObservation(
        record: QuotaRefreshRecord?,
        now: Long,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord {
        val r = record ?: QuotaRefreshRecord(attemptedAt = Long.MIN_VALUE)
        return r.copy(
            succeededAt = now,
            error = null,
            consecutiveFailures = 0,
            nextAllowedAt = maxOf(r.nextAllowedAt ?: Long.MIN_VALUE, now + policy.normalIntervalMs),
        )
    }

    fun recordFailure(
        record: QuotaRefreshRecord?,
        error: QuotaError,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord {
        val base = record ?: QuotaRefreshRecord(attemptedAt = now)
        val failures = base.consecutiveFailures + 1
        val step = policy.backoffMs[minOf(failures - 1, policy.backoffMs.size - 1)]
        var next = now + step
        var cooldown = base.serverCooldownUntil
        error.retryAfter?.let {
            cooldown = it
            next = maxOf(next, it)
        }
        if (error.kind == QuotaError.Kind.notFound || error.kind == QuotaError.Kind.unsupportedResponse) {
            next = maxOf(next, now + policy.normalIntervalMs)
        }
        return base.copy(
            attemptedAt = now,
            error = error,
            consecutiveFailures = failures,
            serverCooldownUntil = cooldown,
            nextAllowedAt = next,
            lastForcedAt = if (forced) now else base.lastForcedAt,
        )
    }
}

object QuotaCapabilityGate {
    fun shouldProbe(
        record: QuotaCapabilityRecord?,
        now: Long,
        userRequested: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): Boolean {
        if (record == null) return true
        if (userRequested) return true
        return now - record.probedAt >= policy.capabilityProbeIntervalMs
    }
}


object QuotaHeadline {
    fun pick(
        pools: List<QuotaPool>,
        now: Long,
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): Pair<QuotaPool, PoolStatus>? {
        val all = pools.filter { it.kind != QuotaPool.Kind.rateLimit }
            .map { it to QuotaStatusRules.poolStatus(it, now, freshness, thresholds) }
            .filter { it.second.status != QuotaStatus.unknown }
        val rated = withoutSpentCurrencyBalances(all, pool = { it.first }, status = { it.second.status })
        if (rated.isEmpty()) return null
        fun rank(p: QuotaPool): Int = when (p.kind) {
            QuotaPool.Kind.usageWindow -> 3
            QuotaPool.Kind.prepaidBalance -> 2
            QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance -> 1
            QuotaPool.Kind.rateLimit -> 0
        }
        return rated.maxWithOrNull { a, b ->
            when {
                a.second.status != b.second.status -> a.second.status.compareTo(b.second.status)
                (a.second.utilization?.percent ?: MINUS_ONE).compareTo(b.second.utilization?.percent ?: MINUS_ONE) != 0 ->
                    (a.second.utilization?.percent ?: MINUS_ONE).compareTo(b.second.utilization?.percent ?: MINUS_ONE)
                rank(a.first) != rank(b.first) -> rank(a.first).compareTo(rank(b.first))
                else -> b.first.id.rawValue.compareTo(a.first.id.rawValue)
            }
        }
    }

    fun isCurrencyBalance(pool: QuotaPool): Boolean =
        pool.kind == QuotaPool.Kind.prepaidBalance && pool.unit is QuotaUnit.Currency

    fun <T> withoutSpentCurrencyBalances(
        items: List<T>,
        pool: (T) -> QuotaPool?,
        status: (T) -> QuotaStatus,
    ): List<T> {
        fun spent(item: T): Boolean {
            val p = pool(item) ?: return false
            return isCurrencyBalance(p) && status(item) == QuotaStatus.exhausted &&
                p.verdict != QuotaPool.Verdict.rejected
        }
        val funded = items.any { item ->
            val p = pool(item)
            p != null && isCurrencyBalance(p) &&
                (status(item) == QuotaStatus.available || status(item) == QuotaStatus.low)
        }
        return if (funded) items.filterNot(::spent) else items
    }
}
