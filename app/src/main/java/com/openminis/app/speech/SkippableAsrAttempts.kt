package com.openminis.app.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SkippableAsrAttempts(
    private val stallThresholdMs: Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    class Skipped : Exception("ASR attempt skipped by the user")

    private class Attempt(val label: String, val nextEntryId: String?) {
        val result = CompletableDeferred<String>()
        var work: Job? = null
        var stallTimer: Job? = null
    }

    private val lock = Any()
    private val live = LinkedHashMap<String, Attempt>()
    private val stalledIds = LinkedHashSet<String>()

    private val _stalled = MutableStateFlow(false)

    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    var log: ((String) -> Unit)? = null

    private fun publishLocked() {
        _stalled.value = stalledIds.isNotEmpty()
    }

    suspend fun run(
        label: String,
        nextEntryId: String?,
        canSkip: Boolean,
        operation: suspend () -> String,
    ): String {
        val id = UUID.randomUUID().toString()
        val attempt = Attempt(label, nextEntryId)
        synchronized(lock) { live[id] = attempt }
        attempt.work = scope.launch {
            try {
                attempt.result.complete(operation())
            } catch (t: Throwable) {
                attempt.result.completeExceptionally(t)
            }
        }
        if (canSkip) {
            attempt.stallTimer = scope.launch {
                delay(stallThresholdMs)
                synchronized(lock) {
                    if (attempt.result.isActive && live.containsKey(id)) {
                        log?.invoke("ASR stall: $label has no result after ${stallThresholdMs / 1000}s — offering skip")
                        stalledIds.add(id)
                        publishLocked()
                    }
                }
            }
        }
        try {
            return attempt.result.await()
        } catch (c: kotlinx.coroutines.CancellationException) {
            attempt.work?.cancel()
            throw c
        } finally {
            attempt.stallTimer?.cancel()
            synchronized(lock) {
                live.remove(id)
                if (stalledIds.remove(id)) publishLocked()
            }
        }
    }

    fun skipStalled(): List<String?> {
        val skipped = synchronized(lock) {
            val out = stalledIds.mapNotNull { live[it] }
            stalledIds.clear()
            publishLocked()
            out
        }
        for (attempt in skipped) {
            log?.invoke("ASR stall: user skipped ${attempt.label}")
            attempt.work?.cancel()
            attempt.result.completeExceptionally(Skipped())
        }
        return skipped.map { it.nextEntryId }
    }
}
