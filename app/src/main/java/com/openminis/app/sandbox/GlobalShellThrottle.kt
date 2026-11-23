package com.openminis.app.sandbox

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object GlobalShellThrottle {
    private const val TAG = "GlobalShellThrottle"

    const val PROCESS_BUDGET = 16

    @Volatile
    var processBudget: Int = PROCESS_BUDGET
        private set

    fun debugSetProcessBudget(n: Int?): Int {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) return processBudget
        processBudget = (n ?: PROCESS_BUDGET).coerceIn(PROCESSES_PER_COMMAND, 64)
        Log.i(TAG, "[T-loadtest-limits] processBudget=$processBudget")
        return processBudget
    }

    const val PROCESSES_PER_COMMAND = 4

    const val MAX_QUEUE_WAIT_MS = 10 * 60_000L

    private const val RESERVATION_MS = 2_000L

    private const val POLL_MS = 250L
    private const val MAX_RETRY_BACKOFF_MS = 5_000L

    @Volatile
    internal var processCounter: () -> Int = { countDescendants(File("/proc"), android.os.Process.myPid()) }

    @Volatile
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private val admission = Mutex()
    private val running = AtomicInteger(0)
    private val waiting = AtomicInteger(0)
    private class Reservation(val atMs: Long, val liveAtAdmission: Int)
    private val reservations = java.util.concurrent.ConcurrentHashMap<Any, Reservation>()

    val runningCount: Int get() = running.get()

    val waitingCount: Int get() = waiting.get()

    sealed class Outcome<out T> {
        data class Ran<T>(
            val value: T,
            val queuedMs: Long,
            val queued: Boolean,
            val startRetries: Int,
        ) : Outcome<T>()

        data class QueueTimedOut(val waitedMs: Long, val lastLiveCount: Int) : Outcome<Nothing>()
    }

    suspend fun <T> run(
        tag: String,
        maxQueueWaitMs: Long = MAX_QUEUE_WAIT_MS,
        isStartFailure: (T) -> Boolean = { false },
        block: suspend () -> T,
    ): Outcome<T> {
        var queued = false
        var retries = 0
        var lastLive = 0
        var queuedMs = 0L
        while (true) {
            val remaining = maxQueueWaitMs - queuedMs
            val admitStart = clock()
            val liveAtAdmission = if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                admit(tag) { live -> queued = true; lastLive = live }
            }
            queuedMs += clock() - admitStart
            if (liveAtAdmission == null) {
                Log.w(TAG, "[$tag] gave up after ${queuedMs}ms in the queue (live=$lastLive budget=$processBudget)")
                return Outcome.QueueTimedOut(queuedMs, lastLive)
            }
            val token = Any()
            reservations[token] = Reservation(clock(), liveAtAdmission)
            running.incrementAndGet()
            val value = try {
                block()
            } finally {
                reservations.remove(token)
                running.decrementAndGet()
            }
            if (!isStartFailure(value)) {
                if (queued) Log.i(TAG, "[$tag] finished after queueing ${queuedMs}ms (start retries=$retries)")
                return Outcome.Ran(value, if (queued) queuedMs else 0L, queued, retries)
            }
            retries++
            queued = true
            val backoff = (1_000L shl (retries - 1).coerceAtMost(3)).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            if (maxQueueWaitMs - queuedMs <= backoff) {
                Log.w(TAG, "[$tag] gave up after ${queuedMs}ms: the command could not be started ($retries attempts)")
                return Outcome.QueueTimedOut(queuedMs, safeCount())
            }
            Log.w(TAG, "[$tag] start failed (attempt $retries), re-queueing in ${backoff}ms (live=${safeCount()})")
            val backoffStart = clock()
            delay(backoff)
            queuedMs += clock() - backoffStart
        }
    }

    private suspend fun admit(tag: String, onQueued: (live: Int) -> Unit): Int {
        var announced = false
        fun announce(live: Int, why: String) {
            if (announced) return
            announced = true
            onQueued(live)
            Log.i(TAG, "[$tag] queued: $why (live=$live running=${running.get()} waiting=${waiting.get()} budget=$processBudget)")
        }
        waiting.incrementAndGet()
        try {
            val lockStart = clock()
            return admission.withLock {
                if (clock() - lockStart >= POLL_MS) announce(safeCount(), "behind earlier queued commands")
                val live = awaitRoom(::announce)
                if (announced) Log.i(TAG, "[$tag] dequeued after ${clock() - lockStart}ms (live=$live)")
                live
            }
        } finally {
            waiting.decrementAndGet()
        }
    }

    private suspend fun awaitRoom(announce: (live: Int, why: String) -> Unit): Int {
        while (true) {
            val live = safeCount()
            val reserved = reservedProcesses(live)
            if (running.get() == 0 || live + reserved + PROCESSES_PER_COMMAND <= processBudget) return live
            announce(live, "process budget full")
            delay(POLL_MS)
        }
    }

    private fun reservedProcesses(live: Int): Int {
        val now = clock()
        val young = reservations.values.filter { now - it.atMs < RESERVATION_MS }
        if (young.isEmpty()) return 0
        val grown = (live - young.minOf { it.liveAtAdmission }).coerceAtLeast(0)
        return (young.size * PROCESSES_PER_COMMAND - grown).coerceAtLeast(0)
    }

    private fun safeCount(): Int = runCatching { processCounter() }.getOrElse {
        Log.w(TAG, "process count failed: ${it.message}")
        0
    }

    internal fun countDescendants(procRoot: File, rootPid: Int): Int {
        val parentOf = HashMap<Int, Int>()
        procRoot.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            parseParentPid(stat)?.let { parentOf[pid] = it }
        }
        val children = HashMap<Int, MutableList<Int>>()
        for ((pid, ppid) in parentOf) children.getOrPut(ppid) { ArrayList() }.add(pid)
        var count = 0
        val stack = ArrayDeque(children[rootPid].orEmpty())
        val seen = HashSet<Int>()
        while (stack.isNotEmpty()) {
            val pid = stack.removeLast()
            if (!seen.add(pid)) continue
            count++
            children[pid]?.let { stack.addAll(it) }
        }
        return count
    }

    internal fun parseParentPid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        return fields.getOrNull(1)?.toIntOrNull()
    }
}
