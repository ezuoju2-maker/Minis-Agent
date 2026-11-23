package com.openminis.app.usage

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.UsageRowRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

object UsageReportLoader {

    const val ROWS_TTL_MS = 30_000L

    private class Rows(val sinceMs: Long, val readAtMs: Long, val rows: List<UsageRow>)

    @Volatile private var cached: Rows? = null

    suspend fun load(dao: ChatDao, query: UsageQuery, fresh: Boolean = true): UsageReport {
        val since = sinceMs(query)
        val now = System.currentTimeMillis()
        val hit = cached?.takeIf { !fresh && it.sinceMs <= since && now - it.readAtMs in 0 until ROWS_TTL_MS }
        val rows = if (hit != null) {
            if (hit.sinceMs == since) hit.rows else hit.rows.filter { it.atMs >= since }
        } else {
            val records = dao.usageRowsSince(since)
            withContext(Dispatchers.Default) { records.map { it.toUsageRow() } }
                .also { cached = Rows(since, now, it) }
        }
        return withContext(Dispatchers.Default) {
            UsageReportBuilder.build(rows, query)
        }
    }

    internal fun sinceMs(q: UsageQuery): Long {
        val today = Instant.ofEpochMilli(q.nowMs).atZone(q.zone).toLocalDate()
        val first = when (q.range) {
            UsageRange.TODAY -> today
            UsageRange.LAST_7_DAYS -> today.minusDays(6)
            UsageRange.LAST_30_DAYS -> today.minusDays(29)
            UsageRange.ALL -> return 0L
        }
        return first.atStartOfDay(q.zone).toInstant().toEpochMilli()
    }

    internal fun UsageRowRecord.toUsageRow() = UsageRow(
        atMs = createdAt,
        sessionId = sessionId,
        modelId = modelId,
        sessionModelId = sessionModelId,
        modelDisplayName = modelDisplayName,
        providerType = providerType,
        providerInstanceId = providerInstanceId,
        usageJson = tokenUsage,
    )
}
