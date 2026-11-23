package com.openminis.app.speech.correction

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object ScreenContextBuilder {

    private const val TAG = "CorrectionContext"

    enum class Kind(val label: String) { USER("用户"), ASSISTANT("AI"), TOOL("工具") }

    data class Segment(
        val key: String,
        val messageId: String,
        val kind: Kind?,
        val text: String,
    )

    data class VisibleItem(val key: String, val offset: Int, val size: Int)

    data class Snapshot(
        val segments: List<Segment>,
        val visible: List<VisibleItem>,
        val viewportStart: Int,
        val viewportEnd: Int,
        val olderAtLargerOffsets: Boolean,
    )

    data class LatestReply(val messageId: String, val text: String)

    data class Picked(val index: Int, val kind: Kind, val text: String, val distance: Int)

    fun selectWindow(snapshot: Snapshot, excludeMessageId: String?): Pair<List<Picked>, Boolean> {
        val segments = snapshot.segments
        val height = snapshot.viewportEnd - snapshot.viewportStart
        if (segments.isEmpty() || height <= 0) return emptyList<Picked>() to false
        val indexByKey = HashMap<String, Int>(segments.size * 2)
        segments.forEachIndexed { i, s -> indexByKey[s.key] = i }
        val visible = snapshot.visible
            .mapNotNull { v -> indexByKey[v.key]?.let { it to v } }
            .sortedBy { it.first }
        if (visible.isEmpty()) return emptyList<Picked>() to false

        val center = (snapshot.viewportStart + snapshot.viewportEnd) / 2.0
        val reach = height * (0.5 + CorrectionContextBudget.SCREEN_EXTENSION_SCREENS)

        val olderAtLarger = if (visible.size >= 2) {
            visible.first().second.offset > visible.last().second.offset
        } else {
            snapshot.olderAtLargerOffsets
        }

        fun signedCenterOf(v: VisibleItem): Double {
            val mid = v.offset + v.size / 2.0
            return if (olderAtLarger) center - mid else mid - center
        }

        val distance = DoubleArray(segments.size) { Double.NaN }
        for ((i, v) in visible) distance[i] = signedCenterOf(v)

        val textVisible = visible.filter { segments[it.first].text.isNotBlank() }
        val chars = textVisible.sumOf { segments[it.first].text.length }
        val px = textVisible.sumOf { it.second.size }
        val pxPerChar = if (chars > 0 && px > 0) px.toDouble() / chars else height / 400.0
        val minRowPx = height / 20.0
        fun estimate(i: Int): Double {
            val t = segments[i].text
            return if (t.isBlank()) minRowPx else max(minRowPx, t.length * pxPerChar)
        }

        val (loIdx, loItem) = visible.first()
        val (hiIdx, hiItem) = visible.last()
        var edge = signedCenterOf(loItem) - loItem.size / 2.0
        for (i in loIdx - 1 downTo 0) {
            if (!distance[i].isNaN()) continue
            val h = estimate(i)
            distance[i] = edge - h / 2.0
            edge -= h
            if (-edge > reach) break
        }
        edge = signedCenterOf(hiItem) + hiItem.size / 2.0
        for (i in hiIdx + 1 until segments.size) {
            if (!distance[i].isNaN()) continue
            val h = estimate(i)
            distance[i] = edge + h / 2.0
            edge += h
            if (edge > reach) break
        }

        var excludedInWindow = false
        val picks = mutableListOf<Picked>()
        for (i in segments.indices) {
            val d = distance[i]
            if (d.isNaN() || abs(d) > reach) continue
            val s = segments[i]
            val kind = s.kind ?: continue
            if (s.text.isBlank()) continue
            if (excludeMessageId != null && s.messageId == excludeMessageId && kind == Kind.ASSISTANT) {
                excludedInWindow = true
                continue
            }
            picks.add(Picked(i, kind, s.text.trim(), d.roundToInt()))
        }
        return picks.sortedBy { abs(it.distance) } to excludedInWindow
    }

    fun pack(picks: List<Picked>, budget: Int): List<String> {
        val kept = mutableListOf<Pair<Int, String>>()
        var left = budget
        for (p in picks) {
            val sep = if (kept.isEmpty()) 0 else 1
            val label = "【${p.kind.label}】"
            val full = label + p.text
            val room = left - sep
            val line = when {
                full.length <= room -> full
                room - label.length - 1 >= MIN_PARTIAL -> {
                    val textRoom = room - label.length - 1
                    if (p.distance < 0) label + "…" + p.text.takeLast(textRoom)
                    else label + p.text.take(textRoom) + "…"
                }
                else -> continue
            }
            kept.add(p.index to line)
            left -= line.length + sep
            if (left < MIN_PARTIAL) break
        }
        return kept.sortedBy { it.first }.map { it.second }
    }

    fun capLatestReply(text: String, budget: Int): String {
        val t = text.trim()
        if (t.length <= budget) return t
        val head = budget / 3
        val tail = budget - head - 1
        return t.take(head) + "…" + t.takeLast(tail)
    }

    fun build(snapshot: Snapshot?, latest: LatestReply?): ScreenContext? {
        val started = System.currentTimeMillis()
        val (picks, latestOnScreen) = if (snapshot != null) {
            selectWindow(snapshot, latest?.messageId)
        } else {
            emptyList<Picked>() to false
        }
        val lines = pack(picks, CorrectionContextBudget.SCREEN_VIEWPORT)
        val reply = latest?.text?.takeIf { it.isNotBlank() }
            ?.let { capLatestReply(it, CorrectionContextBudget.SCREEN_LATEST_REPLY) }
        if (lines.isEmpty() && reply == null) return null
        val result = ScreenContext(lines, reply, latestOnScreen && reply != null)
        Log.i(
            TAG,
            "[Screen] visibleRows=${snapshot?.visible?.size ?: 0} windowRows=${picks.size} " +
                "keptLines=${lines.size} viewportChars=${result.viewportChars}/${CorrectionContextBudget.SCREEN_VIEWPORT} " +
                "latestReplyChars=${reply?.length ?: 0}/${CorrectionContextBudget.SCREEN_LATEST_REPLY} " +
                "latestReplyOnScreen=${result.latestReplyOnScreen} durationMs=${System.currentTimeMillis() - started}",
        )
        return result
    }

    const val MIN_PARTIAL = 40
}

data class ScreenContext(
    val viewportLines: List<String>,
    val latestReply: String?,
    val latestReplyOnScreen: Boolean,
) {
    val viewportChars: Int get() = viewportLines.sumOf { it.length }
}
