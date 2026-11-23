package com.openminis.app.agent

class DeadlineBudget(
    private val totalMs: Long,
    private val clock: () -> Long,
    private val startAt: Long = clock(),
) {
    private val deadline: Long = saturatedAdd(startAt, totalMs.coerceAtLeast(0L))

    fun remaining(now: Long = clock()): Long =
        if (now >= deadline) 0L else deadline - now

    fun expired(now: Long = clock()): Boolean = remaining(now) <= 0L

    fun consumedFraction(now: Long = clock()): Double {
        if (totalMs <= 0L) return 1.0
        val elapsed = (now - startAt).toDouble()
        return (elapsed / totalMs.toDouble()).coerceIn(0.0, 1.0)
    }

    fun sliceFor(desiredMs: Long, minMs: Long = 1_000L, now: Long = clock()): Long {
        val want = desiredMs.coerceAtLeast(0L)
        val floor = minMs.coerceAtLeast(0L)
        if (want == 0L) return 0L
        val left = remaining(now)
        if (left < floor || left == 0L) return 0L
        val fairShare = left / 2
        return minOf(want, maxOf(floor, fairShare)).coerceAtMost(left)
    }

    enum class Urgency { NORMAL, WRAP_UP, CRITICAL }
    fun urgency(now: Long = clock()): Urgency {
        if (totalMs <= 0L) return Urgency.CRITICAL
        val left = remaining(now)
        return when {
            left <= (totalMs / 10).coerceAtLeast(1L) -> Urgency.CRITICAL
            left <= totalMs / 3 -> Urgency.WRAP_UP
            else -> Urgency.NORMAL
        }
    }

    fun hint(now: Long = clock()): String? = when (urgency(now)) {
        Urgency.NORMAL -> null
        Urgency.WRAP_UP -> "Time budget ~${remaining(now) / 1000}s left: wrap up current step, save progress, avoid new long operations."
        Urgency.CRITICAL -> "Time budget nearly exhausted (~${remaining(now) / 1000}s): stop new work and produce the final summary now."
    }

    private companion object {
        fun saturatedAdd(a: Long, b: Long): Long {
            val sum = a + b
            return when {
                b > 0 && sum < a -> Long.MAX_VALUE
                b < 0 && sum > a -> Long.MIN_VALUE
                else -> sum
            }
        }
    }
}
