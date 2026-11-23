package com.openminis.app.ui.settings.backup

class RestoreEta(
    private val windowSize: Int = 8,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Sample(val atMs: Long, val done: Int)

    private val samples = ArrayDeque<Sample>()
    private var categoryKey: String? = null

    fun update(categoryKey: String, done: Int, total: Int?): Long? {
        if (categoryKey != this.categoryKey) {
            this.categoryKey = categoryKey
            samples.clear()
        }
        val last = samples.lastOrNull()
        if (last != null && done < last.done) samples.clear()

        samples.addLast(Sample(nowMs(), done))
        while (samples.size > windowSize) samples.removeFirst()

        if (total == null || total <= 0) return null
        val remaining = total - done
        if (remaining <= 0) return 0L

        val first = samples.first()
        val cur = samples.last()
        val elapsedMs = cur.atMs - first.atMs
        val progressed = cur.done - first.done
        if (samples.size < 2 || elapsedMs <= 0L || progressed <= 0) return null

        val perMs = progressed.toDouble() / elapsedMs
        if (perMs <= 0.0) return null
        return Math.round(remaining / perMs / 1000.0).coerceAtLeast(0L)
    }

    fun reset() {
        samples.clear()
        categoryKey = null
    }

    companion object {
        fun bucket(seconds: Long?): EtaBucket? = when {
            seconds == null -> null
            seconds <= 3L -> EtaBucket.AlmostDone
            seconds < 60L -> EtaBucket.Seconds(seconds)
            else -> EtaBucket.MinutesSeconds(seconds / 60L, seconds % 60L)
        }
    }

    sealed class EtaBucket {
        object AlmostDone : EtaBucket()
        data class Seconds(val seconds: Long) : EtaBucket()
        data class MinutesSeconds(val minutes: Long, val seconds: Long) : EtaBucket()
    }
}
