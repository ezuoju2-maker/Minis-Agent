package com.openminis.app.agent

/**
 * [long-tasks / stability] A shared wall-clock budget for a multi-step task.
 *
 * A long agent task issues many tool calls. Without a shared deadline, each
 * tool gets its own generous timeout and the task can run effectively forever;
 * with a hard per-tool timeout it can't finish legitimate long work. This
 * budget gives the task ONE deadline and hands each step a *slice* of what's
 * left, so:
 *
 *  - total runtime is bounded (no wedged sessions),
 *  - early cheap steps don't starve later ones (unused budget carries over),
 *  - when the remaining budget is too small for a step's minimum, the caller
 *    is told to wrap up instead of starting work it cannot finish.
 *
 * Pure clock-injected logic (no System.currentTimeMillis inside), so it is
 * deterministic and fully unit-testable.
 *
 * v2.0 audit fixes:
 *  - [FIX-08] startAt + totalMs 的 Long 溢出会让 deadline 变负、任务立刻"超时"；
 *    现在做饱和加法；
 *  - [FIX-09] remaining() 用减法比较，避免 now 极大时的下溢；
 *  - [FIX-10] urgency() 在 totalMs<=0 / 极小预算下不再产生除零与误判；
 *  - [FIX-11] sliceFor() 显式处理 desiredMs<=0 与 minMs<=0。
 */
class DeadlineBudget(
    private val totalMs: Long,
    private val clock: () -> Long,
    private val startAt: Long = clock(),
) {
    private val deadline: Long = saturatedAdd(startAt, totalMs.coerceAtLeast(0L))

    /** Milliseconds left before the deadline (never negative). */
    fun remaining(now: Long = clock()): Long =
        if (now >= deadline) 0L else deadline - now

    fun expired(now: Long = clock()): Boolean = remaining(now) <= 0L

    /** Fraction of the total budget consumed, in [0,1]. */
    fun consumedFraction(now: Long = clock()): Double {
        if (totalMs <= 0L) return 1.0
        val elapsed = (now - startAt).toDouble()
        return (elapsed / totalMs.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * Slice for the next step: the smaller of [desiredMs] and a fair share of
     * what's left, never below [minMs] unless the budget is nearly gone.
     * Returns 0 when fewer than [minMs] remain — the caller should stop.
     */
    fun sliceFor(desiredMs: Long, minMs: Long = 1_000L, now: Long = clock()): Long {
        val want = desiredMs.coerceAtLeast(0L)
        val floor = minMs.coerceAtLeast(0L)
        if (want == 0L) return 0L
        val left = remaining(now)
        if (left < floor || left == 0L) return 0L
        val fairShare = left / 2 // leave room for at least one more step
        return minOf(want, maxOf(floor, fairShare)).coerceAtMost(left)
    }

    /** Advisory urgency used to tell the agent to summarise/wrap up. */
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

    /** Human hint to inject when time is short, or null when there's no pressure. */
    fun hint(now: Long = clock()): String? = when (urgency(now)) {
        Urgency.NORMAL -> null
        Urgency.WRAP_UP -> "Time budget ~${remaining(now) / 1000}s left: wrap up current step, save progress, avoid new long operations."
        Urgency.CRITICAL -> "Time budget nearly exhausted (~${remaining(now) / 1000}s): stop new work and produce the final summary now."
    }

    private companion object {
        /** Saturating addition: never wraps past Long.MAX_VALUE. */
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
