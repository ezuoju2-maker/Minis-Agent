package com.openminis.app.ui.chat

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object TableColumnPlanner {

    data class Column(val natural: Float, val minUnbreakable: Float, val hardMin: Float = 0f)

    enum class Mode { NATURAL, WRAPPED, SCROLLS }

    data class Plan(val widths: FloatArray, val tableWidth: Float, val mode: Mode) {
        override fun equals(other: Any?): Boolean = other is Plan && widths.contentEquals(other.widths) &&
            tableWidth == other.tableWidth && mode == other.mode

        override fun hashCode(): Int = widths.contentHashCode()
    }

    const val FLOOR_WIDTH = 80f
    const val COMFORT_FRACTION = 0.35f
    const val SCROLL_CAP_FRACTION = 0.7f
    const val PEEK_MIN = 24f

    fun plan(columns: List<Column>, availableWidth: Float): Plan {
        val n = columns.size
        val w = max(availableWidth, 0f)
        if (n == 0 || w <= 0f) return Plan(FloatArray(0), w, Mode.NATURAL)
        val floorW = min(FLOOR_WIDTH, w)
        val scrollCap = floor(w * SCROLL_CAP_FRACTION)
        val comfortW = floor(w * COMFORT_FRACTION)
        val natural = FloatArray(n) { max(floorW, ceil(columns[it].natural)) }
        val hard = FloatArray(n) { i ->
            min(natural[i], maxOf(floorW, min(ceil(columns[i].minUnbreakable), scrollCap), ceil(columns[i].hardMin)))
        }
        val comfort = FloatArray(n) { i -> min(natural[i], max(hard[i], comfortW)) }
        val sumNatural = natural.sum()
        val sumComfort = comfort.sum()

        if (sumNatural <= w) {
            return Plan(FloatArray(n) { natural[it] * w / sumNatural }, w, Mode.NATURAL)
        }
        if (sumComfort <= w) {
            fun widths(t: Float) = FloatArray(n) {
                max(hard[it], min(natural[it], comfortW + (natural[it] - comfortW) * t))
            }
            var lo = 0f
            var hi = 1f
            repeat(40) {
                val mid = (lo + hi) / 2
                if (widths(mid).sum() < w) lo = mid else hi = mid
            }
            val result = widths(hi)
            val widest = natural.indices.maxByOrNull { natural[it] }!!
            result[widest] += w - result.sum()
            return Plan(result, w, Mode.WRAPPED)
        }
        val widths = FloatArray(n) { max(comfort[it], min(natural[it], scrollCap)) }
        applyPeek(widths, natural, comfort, w)
        return Plan(widths, max(widths.sum(), w), Mode.SCROLLS)
    }

    fun applyPeek(widths: FloatArray, natural: FloatArray, comfort: FloatArray, viewport: Float) {
        var x = 0f
        for (k in widths.indices) {
            if (x + widths[k] > viewport) {
                val visible = viewport - x
                if (k < 1 || visible >= PEEK_MIN) return
                val j = k - 1
                val lessText = widths.indices.filter { it != j && natural[it] <= natural[j] }
                    .maxOfOrNull { widths[it] } ?: 0f
                val give = min(PEEK_MIN - visible, widths[j] - max(comfort[j], lessText))
                if (give > 0f) widths[j] -= give
                return
            }
            x += widths[k]
        }
    }

    fun applyingStreamingFloor(plan: Plan, previous: FloatArray?, availableWidth: Float): Plan {
        if (previous == null || previous.size != plan.widths.size || plan.widths.isEmpty()) return plan
        val widths = FloatArray(plan.widths.size) { max(plan.widths[it], previous[it]) }
        if (plan.mode != Mode.SCROLLS) {
            var over = widths.sum() - max(availableWidth, plan.widths.sum())
            while (over >= 0.5f) {
                val i = widths.indices.maxByOrNull { widths[it] - plan.widths[it] } ?: break
                val lifted = widths[i] - plan.widths[i]
                if (lifted <= 0f) break
                val take = min(over, lifted)
                widths[i] -= take
                over -= take
            }
        }
        return Plan(widths, max(widths.sum(), availableWidth), plan.mode)
    }

    fun longestUnbreakableRun(text: String): String? {
        var best: String? = null
        var start = -1
        var i = 0
        fun close(end: Int) {
            if (start < 0) return
            val run = text.substring(start, end)
            if (run.codePointCount(0, run.length) > (best?.let { it.codePointCount(0, it.length) } ?: 0)) best = run
            start = -1
        }
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val breaks = Character.isWhitespace(cp) || isCjk(cp)
            if (breaks) close(i) else if (start < 0) start = i
            i += Character.charCount(cp)
        }
        close(text.length)
        val b = best ?: return null
        return if (b.codePointCount(0, b.length) > 1) b else null
    }

    private fun isCjk(cp: Int): Boolean = cp in 0x1100..0x11FF || cp in 0x2E80..0x2FDF ||
        cp in 0x3000..0x303F || cp in 0x3040..0x30FF || cp in 0x3100..0x31FF ||
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xAC00..0xD7AF ||
        cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFFEF ||
        cp in 0x20000..0x2FA1F
}
