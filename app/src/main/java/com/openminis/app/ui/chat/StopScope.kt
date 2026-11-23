package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

data class StopSummary(
    val runningTools: List<String> = emptyList(),
    val subAgentsRunning: Int = 0,
    val subAgentsQueued: Int = 0,
    val scheduledTasks: Int = 0,
) {
    val hasBackground: Boolean get() = subAgentsRunning > 0 || subAgentsQueued > 0 || scheduledTasks > 0
}

enum class ToolOnlyStop { INTERRUPT_TOOLS, END_TURN }

object StopScopePolicy {

    fun toolOnlyStop(toolsRunning: Boolean): ToolOnlyStop =
        if (toolsRunning) ToolOnlyStop.INTERRUPT_TOOLS else ToolOnlyStop.END_TURN

    fun endsTurnAtBoundary(stopRequestedThisTurn: Boolean, insertPending: Boolean): Boolean =
        stopRequestedThisTurn && !insertPending

    fun scheduledCancelledNotice(labels: List<String>): String? {
        if (labels.isEmpty()) return null
        val names = labels.joinToString(", ") { "\"${it.take(80)}\"" }
        return "<system-reminder>The user pressed Stop All in this conversation. This also cancelled " +
            "${labels.size} scheduled task(s) created here: $names. They are disabled and will not fire again " +
            "unless the user turns them back on - do not wait for them or reschedule them unless asked." +
            "</system-reminder>"
    }
}

class ForegroundToolInterrupts {

    private class Entry(val name: String, val job: Job, val cancellable: Boolean)

    private val running = ConcurrentHashMap<String, Entry>()
    private val interrupted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    data class Outcome<T>(val value: T, val interrupted: Boolean)

    fun runningNames(): List<String> = running.values.map { it.name }

    fun isEmpty(): Boolean = running.isEmpty()

    fun isRunning(id: String): Boolean = running.containsKey(id)

    fun nameOf(id: String): String? = running[id]?.name

    fun isInterrupted(id: String): Boolean = interrupted.contains(id)

    fun idsNamed(name: String): List<String> = running.entries.filter { it.value.name == name }.map { it.key }

    suspend fun <T> run(
        id: String,
        name: String,
        cancellable: Boolean,
        onInterrupted: () -> T,
        body: suspend () -> T,
    ): Outcome<T> = coroutineScope {
        val call = async { body() }
        running[id] = Entry(name, call, cancellable)
        try {
            val value = call.await()
            Outcome(value, interrupted.contains(id))
        } catch (e: CancellationException) {
            if (interrupted.contains(id) && currentCoroutineContext().isActive) {
                Outcome(onInterrupted(), true)
            } else {
                throw e
            }
        } finally {
            running.remove(id)
            interrupted.remove(id)
        }
    }

    fun interrupt(ids: Collection<String>? = null): List<String> {
        val targets = ids?.filter { running.containsKey(it) } ?: running.keys.toList()
        for (id in targets) {
            interrupted.add(id)
            val entry = running[id] ?: run { interrupted.remove(id); null } ?: continue
            if (entry.cancellable) entry.job.cancel(CancellationException("stopped by the user (tool only)"))
        }
        return targets
    }

    companion object {
        const val INTERRUPTED_RESULT =
            "[Stopped by the user] This tool call was interrupted before it finished; any output above is partial. " +
                "Do not run it again on your own - wait for the user's next message."

        fun withInterruptNote(output: String): String =
            if (output.isBlank()) INTERRUPTED_RESULT else "$output\n\n$INTERRUPTED_RESULT"
    }
}
