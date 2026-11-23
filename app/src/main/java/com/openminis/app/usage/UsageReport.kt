package com.openminis.app.usage

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters


enum class UsageRange { TODAY, LAST_7_DAYS, LAST_30_DAYS, ALL }

enum class UsageGroupBy { PROVIDER, MODEL }

enum class UsageGranularity { HOUR, DAY, WEEK, MONTH }

data class UsageRow(
    val atMs: Long,
    val sessionId: String,
    val modelId: String?,
    val sessionModelId: String? = null,
    val modelDisplayName: String? = null,
    val providerType: String? = null,
    val providerInstanceId: String? = null,
    val usageJson: String?,
)

data class LiveInstance(val id: String, val providerType: String, val name: String)

data class LiveModel(val id: String, val providerType: String)

data class UsageQuery(
    val range: UsageRange,
    val zone: ZoneId,
    val nowMs: Long,
    val groupBy: UsageGroupBy = UsageGroupBy.PROVIDER,
    val exactModelId: Boolean = false,
    val firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
    val liveInstances: List<LiveInstance> = emptyList(),
    val liveModels: List<LiveModel> = emptyList(),
)

data class Tokens(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
) {
    val total: Long get() = input + cacheRead + cacheWrite + output
    val nonCached: Long get() = input + cacheWrite + output
    val inputInclCache: Long get() = input + cacheRead + cacheWrite

    operator fun plus(u: StoredUsage) = Tokens(input + u.input, cacheRead + u.cacheRead, cacheWrite + u.cacheWrite, output + u.output)
}

private fun hitRate(num: Long, den: Long): Double? = if (den > 0) num.toDouble() / den else null

data class UsageTotals(
    val tokens: Tokens,
    val reasoning: Long?,
    val reasoningComplete: Boolean,
    val hitNum: Long,
    val hitDen: Long,
) {
    val hitRate: Double? get() = hitRate(hitNum, hitDen)
    val cacheBasis: Double? get() = tokens.inputInclCache.takeIf { it > 0 }?.let { hitDen.toDouble() / it }
}

data class UsageBucket(val startMs: Long, val endMs: Long, val tokens: Tokens)

data class UsageGroup(
    val key: String,
    val label: String?,
    val tokens: Tokens,
    val hitNum: Long,
    val hitDen: Long,
    val rawIds: List<String> = emptyList(),
    val children: List<UsageGroup> = emptyList(),
    val modelCount: Int = 0,
    val providerCount: Int = 0,
) {
    val hitRate: Double? get() = hitRate(hitNum, hitDen)
}

data class UsageSkipped(val copied: Int = 0, val invalid: Int = 0, val noUsage: Int = 0)

data class UsageReport(
    val query: UsageQuery,
    val rangeStartMs: Long?,
    val rangeEndMs: Long?,
    val granularity: UsageGranularity?,
    val skipped: UsageSkipped,
    val totals: UsageTotals,
    val activeDays: Int,
    val sessions: Int,
    val calendarDays: Int,
    val dailyAvg: Double?,
    val series: List<UsageBucket>,
    val groups: List<UsageGroup>,
)

object UsageReportBuilder {
    const val UNKNOWN_PROVIDER = "unknown"
    const val UNKNOWN_MODEL = ""
    const val REMOVED_INSTANCE = "removed"
    const val UNRECORDED_INSTANCE = "unrecorded"

    private const val MAX_DAY_BUCKETS = 60
    private const val MAX_WEEK_BUCKETS = 26

    private class Counted(val index: Int, val row: UsageRow, val usage: StoredUsage, val model: String?, val provider: String) {
        val cohort = provider to ModelKey.merged(model ?: "")
    }

    fun build(rows: List<UsageRow>, q: UsageQuery): UsageReport {
        var copied = 0; var invalid = 0; var noUsage = 0
        val counted = ArrayList<Counted>(rows.size)
        val liveById = q.liveInstances.associateBy { it.id }
        rows.forEachIndexed { i, row ->
            when (val p = StoredUsageJson.parse(row.usageJson)) {
                StoredUsageParse.NoUsage -> noUsage++
                StoredUsageParse.Invalid -> invalid++
                is StoredUsageParse.Valid -> if (p.usage.copied) copied++ else {
                    val model = row.modelId ?: row.sessionModelId
                    counted += Counted(i, row, p.usage, model, resolveProvider(row, model, liveById, q.liveModels))
                }
            }
        }

        val today = Instant.ofEpochMilli(q.nowMs).atZone(q.zone).toLocalDate()
        val endMs = startOf(today.plusDays(1), q.zone)
        val startDate: LocalDate? = when (q.range) {
            UsageRange.TODAY -> today
            UsageRange.LAST_7_DAYS -> today.minusDays(6)
            UsageRange.LAST_30_DAYS -> today.minusDays(29)
            UsageRange.ALL -> counted.filter { it.row.atMs < endMs }.minOfOrNull { it.row.atMs }
                ?.let { Instant.ofEpochMilli(it).atZone(q.zone).toLocalDate() }
        }
        val startMs = startDate?.let { startOf(it, q.zone) }
        val included = if (startMs == null) emptyList() else counted.filter { it.row.atMs in startMs until endMs }
        val calendarDays = startDate?.let { (ChronoUnit.DAYS.between(it, today) + 1).toInt() } ?: 0

        val granularity = startDate?.let { granularityFor(q.range, it, today, q.firstWeekday) }
        val bounds = if (startDate == null || granularity == null) emptyList()
        else bucketBounds(granularity, startDate, today, q)
        val series = if (bounds.isEmpty()) emptyList() else {
            val sums = Array(bounds.size - 1) { Tokens() }
            for (c in included) {
                val idx = bounds.binarySearch(c.row.atMs).let { if (it >= 0) it else -it - 2 }
                if (idx in sums.indices) sums[idx] = sums[idx] + c.usage
            }
            sums.mapIndexed { i, t -> UsageBucket(bounds[i], bounds[i + 1], t) }
        }

        val cohortsWithCache = included.filter { it.usage.cacheRead + it.usage.cacheWrite > 0 }.mapTo(HashSet()) { it.cohort }
        val capable: (Counted) -> Boolean = { c ->
            c.usage.cacheReported == true || c.usage.cacheRead + c.usage.cacheWrite > 0 ||
                (c.usage.cacheReported == null && c.cohort in cohortsWithCache)
        }
        val totals = totalsOf(included, capable)
        val nonZero = included.filter { it.usage.total > 0 }
        val activeDays = nonZero.mapTo(HashSet()) { Instant.ofEpochMilli(it.row.atMs).atZone(q.zone).toLocalDate() }.size
        val sessions = nonZero.mapTo(HashSet()) { it.row.sessionId }.size
        val dailyAvg = if (calendarDays > 0) totals.tokens.total.toDouble() / calendarDays else null

        val groups = when (q.groupBy) {
            UsageGroupBy.PROVIDER -> providerGroups(included, capable, liveById)
            UsageGroupBy.MODEL -> modelGroups(included, capable, q.exactModelId)
        }

        return UsageReport(
            query = q,
            rangeStartMs = startMs,
            rangeEndMs = if (startMs == null) null else endMs,
            granularity = granularity,
            skipped = UsageSkipped(copied, invalid, noUsage),
            totals = totals,
            activeDays = activeDays,
            sessions = sessions,
            calendarDays = calendarDays,
            dailyAvg = dailyAvg,
            series = series,
            groups = groups,
        )
    }

    private fun resolveProvider(row: UsageRow, model: String?, liveById: Map<String, LiveInstance>, liveModels: List<LiveModel>): String {
        row.providerType?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        row.providerInstanceId?.trim()?.takeIf { it.isNotEmpty() }?.let { liveById[it] }?.let { return it.providerType }
        if (model != null) liveModels.firstOrNull { it.id == model }?.let { return it.providerType }
        return UNKNOWN_PROVIDER
    }

    private fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun weekStart(d: LocalDate, first: DayOfWeek) = d.with(TemporalAdjusters.previousOrSame(first))

    private fun granularityFor(range: UsageRange, start: LocalDate, today: LocalDate, first: DayOfWeek): UsageGranularity = when (range) {
        UsageRange.TODAY -> UsageGranularity.HOUR
        UsageRange.LAST_7_DAYS, UsageRange.LAST_30_DAYS -> UsageGranularity.DAY
        UsageRange.ALL -> when {
            ChronoUnit.DAYS.between(start, today) + 1 <= MAX_DAY_BUCKETS -> UsageGranularity.DAY
            ChronoUnit.WEEKS.between(weekStart(start, first), weekStart(today, first)) + 1 <= MAX_WEEK_BUCKETS -> UsageGranularity.WEEK
            else -> UsageGranularity.MONTH
        }
    }

    private fun bucketBounds(g: UsageGranularity, start: LocalDate, today: LocalDate, q: UsageQuery): List<Long> {
        val out = ArrayList<Long>()
        when (g) {
            UsageGranularity.HOUR -> {
                val end = startOf(today.plusDays(1), q.zone)
                var t = start.atStartOfDay(q.zone)
                while (t.toInstant().toEpochMilli() < end) { out += t.toInstant().toEpochMilli(); t = t.plusHours(1) }
                out += end
            }
            UsageGranularity.DAY -> {
                var d = start
                while (!d.isAfter(today.plusDays(1))) { out += startOf(d, q.zone); d = d.plusDays(1) }
            }
            UsageGranularity.WEEK -> {
                var d = weekStart(start, q.firstWeekday)
                val last = weekStart(today, q.firstWeekday).plusWeeks(1)
                while (!d.isAfter(last)) { out += startOf(d, q.zone); d = d.plusWeeks(1) }
            }
            UsageGranularity.MONTH -> {
                var d = start.withDayOfMonth(1)
                val last = today.withDayOfMonth(1).plusMonths(1)
                while (!d.isAfter(last)) { out += startOf(d, q.zone); d = d.plusMonths(1) }
            }
        }
        return out
    }

    private fun totalsOf(rows: List<Counted>, capable: (Counted) -> Boolean): UsageTotals {
        var t = Tokens(); var hitNum = 0L; var hitDen = 0L
        var reasoning: Long? = null; var anyOutput = false; var allReported = true
        for (c in rows) {
            val u = c.usage
            t += u
            if (capable(c)) { hitNum += u.cacheRead; hitDen += u.input + u.cacheRead + u.cacheWrite }
            u.reasoning?.let { reasoning = (reasoning ?: 0) + it }
            if (u.output > 0) { anyOutput = true; if (u.reasoning == null) allReported = false }
        }
        return UsageTotals(t, reasoning, anyOutput && allReported, hitNum, hitDen)
    }

    private fun groupOf(key: String, label: String?, rows: List<Counted>, capable: (Counted) -> Boolean, rawIds: List<String> = emptyList(), children: List<UsageGroup> = emptyList()): UsageGroup {
        val t = totalsOf(rows, capable)
        return UsageGroup(
            key, label, t.tokens, t.hitNum, t.hitDen, rawIds, children.takeIf { it.size >= 2 } ?: emptyList(),
            modelCount = rows.mapTo(HashSet()) { ModelKey.merged(it.model ?: "") }.size,
            providerCount = rows.mapTo(HashSet()) { it.provider }.size,
        )
    }

    private fun sorted(groups: List<UsageGroup>, pinnedLast: List<String>): List<UsageGroup> =
        groups.filter { it.key !in pinnedLast }.sortedWith(compareByDescending<UsageGroup> { it.tokens.total }.thenBy { it.key }) +
            pinnedLast.mapNotNull { k -> groups.firstOrNull { it.key == k } }

    private fun providerGroups(rows: List<Counted>, capable: (Counted) -> Boolean, liveById: Map<String, LiveInstance>): List<UsageGroup> {
        val groups = rows.groupBy { it.provider }.map { (provider, inGroup) ->
            val children = inGroup.groupBy { c ->
                val id = c.row.providerInstanceId?.trim()?.takeIf { it.isNotEmpty() }
                when {
                    id == null -> UNRECORDED_INSTANCE
                    id in liveById -> id
                    else -> REMOVED_INSTANCE
                }
            }.map { (k, r) -> groupOf(k, liveById[k]?.name, r, capable) }
            groupOf(provider, provider.takeIf { it != UNKNOWN_PROVIDER }, inGroup, capable,
                children = sorted(children, listOf(REMOVED_INSTANCE, UNRECORDED_INSTANCE)))
        }
        return sorted(groups, listOf(UNKNOWN_PROVIDER))
    }

    private fun modelGroups(rows: List<Counted>, capable: (Counted) -> Boolean, exact: Boolean): List<UsageGroup> {
        val groups = rows.groupBy { c -> c.model?.let { ModelKey.of(it, exact) } ?: UNKNOWN_MODEL }.map { (key, inGroup) ->
            val latest = inGroup.maxWithOrNull(compareBy<Counted> { it.row.atMs }.thenBy { it.index })
            val label = latest?.row?.modelDisplayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: latest?.model?.trim()?.takeIf { it.isNotEmpty() }
            val rawIds = inGroup.mapNotNull { it.model?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct().sorted()
            val children = inGroup.groupBy { it.provider }.map { (p, r) -> groupOf(p, p.takeIf { it != UNKNOWN_PROVIDER }, r, capable) }
            groupOf(key, if (key == UNKNOWN_MODEL) null else label, inGroup, capable, rawIds, sorted(children, listOf(UNKNOWN_PROVIDER)))
        }
        return sorted(groups, listOf(UNKNOWN_MODEL))
    }
}
