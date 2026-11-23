package com.openminis.app.sandbox

import com.openminis.app.diagnostics.ThreadNames
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class OffloadInFlight(private val clock: () -> Long = System::currentTimeMillis) {
    private val admitted = AtomicInteger(0)
    private val running = ConcurrentHashMap<Long, Pair<String, Long>>()

    val count: Int get() = admitted.get()

    fun tryAdmit(max: Int): Boolean {
        while (true) {
            val n = admitted.get()
            if (n >= max) return false
            if (admitted.compareAndSet(n, n + 1)) return true
        }
    }

    fun release() { admitted.decrementAndGet() }

    fun begin(command: String) { running[Thread.currentThread().id] = command to clock() }

    fun end() { running.remove(Thread.currentThread().id) }

    fun summary(): String {
        val now = clock()
        val groups = running.values.groupBy({ it.first }, { it.second })
        if (groups.isEmpty()) return "none"
        return groups.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Long>>> { it.value.size }.thenBy { it.key })
            .joinToString(",") { (cmd, starts) -> "$cmd:${starts.size}(oldest ${(now - starts.min()) / 1000}s)" }
    }

    companion object {
        val WORKER_THREAD_NAME = ThreadNames.of("offload-wk")

        fun workerThreadName(command: String): String {
            val short = command.removePrefix("android-").removePrefix("minis-").replace('.', '_')
            return ThreadNames.of("o-$short").take(15)
        }
    }
}
