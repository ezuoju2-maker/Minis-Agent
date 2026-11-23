package com.openminis.app.backup.remote

import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object RcloneRpcTracker {

    private const val TAG = "Rclone"

    private data class InFlight(val method: String, val startMs: Long)

    private val nextToken = AtomicLong()
    private val inFlight = ConcurrentHashMap<Long, InFlight>()

    val inFlightCount: Int get() = inFlight.size

    @Volatile internal var log: (String) -> Unit = { AppLogger.info(TAG, it) }
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    fun <T> track(method: String, block: () -> T): T {
        val token = nextToken.incrementAndGet()
        val start = clock()
        inFlight[token] = InFlight(method, start)
        val others = inFlight.filterKeys { it != token }.values
        val concurrent = others.isNotEmpty()
        if (concurrent) {
            log(
                "[Rclone] concurrent RPC enter: $method with ${others.size + 1} in flight; others=" +
                    others.joinToString(prefix = "[", postfix = "]") { "${it.method} ${start - it.startMs}ms" },
            )
        }
        try {
            return block()
        } finally {
            inFlight.remove(token)
            if (concurrent) {
                log("[Rclone] concurrent RPC exit: $method after ${clock() - start}ms; ${inFlight.size} still in flight")
            }
        }
    }
}
