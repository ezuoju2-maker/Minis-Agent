package com.openminis.app.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

class WebViewWedgedException(message: String) : IllegalStateException(message)

internal object BrowserActionGuard {
    const val JS_EVAL_TIMEOUT_MS = 15_000L

    const val ACTION_DEAD_TIMEOUT_MS = 75_000L

    const val WEDGE_SUSPECT_MS = 30_000L

    const val TAB_SERIAL_WAIT_TIMEOUT_MS = 60_000L

    sealed class Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>()
        data class DeadlineExceeded(val elapsedMs: Long) : Outcome<Nothing>()
        data class Wedged(val message: String) : Outcome<Nothing>()
    }

    suspend fun lockWithin(mutex: Mutex, waitMs: Long): Boolean =
        withTimeoutOrNull(waitMs) { mutex.lock() } != null

    suspend fun <T : Any> runGuarded(
        deadlineMs: Long = ACTION_DEAD_TIMEOUT_MS,
        suspectMs: Long = WEDGE_SUSPECT_MS,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
        onSuspect: (String) -> Unit,
        block: suspend () -> T,
    ): Outcome<T> {
        val startedAt = clock()
        return try {
            val value = withTimeoutOrNull(deadlineMs) { block() }
            if (value != null) {
                Outcome.Done(value)
            } else {
                val elapsed = clock() - startedAt
                onSuspect("action exceeded ${deadlineMs / 1000}s deadline")
                Outcome.DeadlineExceeded(elapsed)
            }
        } catch (e: WebViewWedgedException) {
            Outcome.Wedged(e.message ?: "browser tab stopped responding")
        } catch (e: CancellationException) {
            val elapsed = clock() - startedAt
            if (elapsed >= suspectMs) onSuspect("cancelled after ${elapsed}ms")
            throw e
        }
    }
}
