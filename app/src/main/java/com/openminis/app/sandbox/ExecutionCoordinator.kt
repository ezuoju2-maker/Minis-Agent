package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val queueNote: String? = null,
    )

    @Volatile
    internal var executionStrategy: ShellExecutionStrategy = ShellExecutionStrategy.DEFAULT

    private suspend fun executeFresh(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
        stopEpoch: Long,
        spawned: java.util.concurrent.atomic.AtomicBoolean,
    ): CommandResult {
        val startTime = System.currentTimeMillis()
        val bindMounts = buildSessionBindMounts(fsSessionId ?: sessionId)
        val envVars = envVarRepository?.allAsDict() ?: emptyMap()

        suspend fun attempt(noSeccomp: Boolean): Pair<String, Int> {
            val shell = FreshProcessShell(
                context = appContext,
                sessionId = sessionId,
                sessionBindMounts = bindMounts,
                useNoSeccomp = noSeccomp,
            )
            freshShells.compute(sessionId) { _, existing ->
                (existing ?: newFreshShellSet()).also { it.add(shell) }
            }
            if (stoppedSince(sessionId, stopEpoch)) {
                freshShells.computeIfPresent(sessionId) { _, set ->
                    set.remove(shell)
                    if (set.isEmpty()) null else set
                }
                return Pair(NOT_STARTED_OUTPUT, FreshProcessShell.CANCELLED_EXIT)
            }
            spawned.set(true)
            return try {
                shell.execute(
                    command = command,
                    timeout = timeout,
                    envVars = envVars,
                    lineCallback = lineCallback,
                )
            } finally {
                freshShells.computeIfPresent(sessionId) { _, set ->
                    set.remove(shell)
                    if (set.isEmpty()) null else set
                }
            }
        }

        var (rawOutput, exitCode) = attempt(noSeccomp = false)
        val firstDurationMs = System.currentTimeMillis() - startTime
        if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = exitCode,
                durationMs = firstDurationMs,
                producedOutput = rawOutput.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                SeccompFallbackPolicy.retryLogLine(exitCode, firstDurationMs, "fresh shell command"),
            )
            val retried = attempt(noSeccomp = true)
            rawOutput = retried.first
            exitCode = retried.second
        }
        val durationMs = System.currentTimeMillis() - startTime
        val sanitized = TerminalSanitizer.sanitize(rawOutput)
        val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
        val output = if (exitCode != 0 && exitCode != 124) {
            ShellExitCode.ensureSuffix(truncated, exitCode)
        } else {
            truncated
        }
        return CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
    }

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    private val shells = ConcurrentHashMap<String, PersistentShell>()

    private val freshShells = ConcurrentHashMap<String, MutableSet<FreshProcessShell>>()

    private fun newFreshShellSet(): MutableSet<FreshProcessShell> = ConcurrentHashMap.newKeySet()

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private val mountedSessionIds = ConcurrentHashMap<String, String>()

    fun mountedSessionIdFor(sessionId: String): String = mountedSessionIds[sessionId] ?: sessionId

    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    private val globalLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        fsSessionId: String? = null,
        stopEpoch: Long? = null,
    ): CommandResult {
        val epoch = stopEpoch ?: stopEpoch(sessionId)
        if (stoppedSince(sessionId, epoch)) return notStarted(sessionId, "before it was queued")
        if (!PRootKernel.isBooted) {
            Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
            PRootKernel.boot(appContext)
        }

        if (fsSessionId != null && fsSessionId != sessionId) {
            mountedSessionIds[sessionId] = fsSessionId
        }

        val spawned = java.util.concurrent.atomic.AtomicBoolean(false)
        val outcome = try {
            kotlinx.coroutines.coroutineScope {
                val work = async {
                    GlobalShellThrottle.run(
                        tag = sessionId,
                        isStartFailure = ::isStartFailure,
                    ) {
                        if (stoppedSince(sessionId, epoch)) {
                            CommandResult(output = NOT_STARTED_OUTPUT, exitCode = FreshProcessShell.CANCELLED_EXIT, durationMs = 0L)
                        } else if (executionStrategy == ShellExecutionStrategy.FRESH_PROCESS) {
                            executeFresh(sessionId, command, timeout, lineCallback, fsSessionId, epoch, spawned)
                        } else {
                            spawned.set(true)
                            executeWarm(sessionId, command, timeout, lineCallback, fsSessionId)
                        }
                    }
                }
                val watcher = launch {
                    while (isActive && !spawned.get()) {
                        if (stoppedSince(sessionId, epoch) && !spawned.get()) {
                            work.cancel(StoppedBeforeStart())
                            break
                        }
                        kotlinx.coroutines.delay(STOP_WATCH_MS)
                    }
                }
                try { work.await() } finally { watcher.cancel() }
            }
        } catch (e: StoppedBeforeStart) {
            return notStarted(sessionId, "while waiting to start")
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e.cause is StoppedBeforeStart && kotlinx.coroutines.currentCoroutineContext().isActive) {
                return notStarted(sessionId, "while waiting to start")
            }
            throw e
        }
        if (outcome is GlobalShellThrottle.Outcome.Ran && outcome.value.output == NOT_STARTED_OUTPUT) {
            return notStarted(sessionId, "when it was admitted")
        }
        return when (outcome) {
            is GlobalShellThrottle.Outcome.Ran -> {
                if (!outcome.queued) outcome.value
                else outcome.value.copy(queueNote = queueNote(outcome.queuedMs, outcome.startRetries))
            }
            is GlobalShellThrottle.Outcome.QueueTimedOut -> CommandResult(
                output = "Not run: waited ${outcome.waitedMs / 1000}s for Android's per-app process limit to " +
                    "free up (${outcome.lastLiveCount} DeepSeek processes running, budget " +
                    "${GlobalShellThrottle.PROCESS_BUDGET}), or for the shell to start. Nothing was executed; " +
                    "retry once other commands or background processes have finished.",
                exitCode = 124,
                durationMs = outcome.waitedMs,
            )
        }
    }

    internal fun isStartFailure(result: CommandResult): Boolean =
        result.exitCode == -1 && result.output.startsWith(FreshProcessShell.SPAWN_FAILED_PREFIX)

    internal fun queueNote(queuedMs: Long, startRetries: Int): String {
        val secs = String.format(java.util.Locale.US, "%.1f", queuedMs / 1000.0)
        val retried = if (startRetries > 0) " Its process failed to start $startRetries time(s) and was retried." else ""
        return "<system-reminder>This command was queued for ${secs}s before it started, because Android " +
            "limits how many processes an app may run at once.$retried It then ran to completion and its output " +
            "is complete, but its measured duration may be inaccurate.</system-reminder>"
    }

    private suspend fun executeWarm(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
    ): CommandResult {
        val lease = acquireShellLease(sessionId, fsSessionId ?: sessionId)

        return try {
            val startTime = System.currentTimeMillis()

            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            val shell = lease.shell

            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[lease.key] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[lease.key] = envVars.keys.toSet()
            }

            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (exitCode != 0 && exitCode != 124) {
                ShellExitCode.ensureSuffix(truncated, exitCode)
            } else {
                truncated
            }

            CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
        } finally {
            lease.mutex.unlock()
        }
    }

    private const val MAX_SHELLS_PER_SESSION = 4

    private data class ShellLease(
        val key: String,
        val shell: PersistentShell,
        val mutex: Mutex,
    )

    private suspend fun acquireShellLease(sessionId: String, fsSessionId: String): ShellLease {
        for (slot in 0 until MAX_SHELLS_PER_SESSION) {
            val key = "$sessionId#$slot"
            val mutex = mutexes.getOrPut(key) { Mutex() }
            if (!mutex.tryLock()) continue
            val existing = shells[key]
            val shell = if (existing != null && existing.isAlive) existing
            else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
            return ShellLease(key, shell, mutex)
        }
        val key = "$sessionId#0"
        val mutex = mutexes.getOrPut(key) { Mutex() }
        mutex.lock()
        val existing = shells[key]
        val shell = if (existing != null && existing.isAlive) existing
        else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
        return ShellLease(key, shell, mutex)
    }

    private suspend fun getOrCreateShell(
        sessionId: String,
        fsSessionId: String = sessionId,
        chatSessionId: String = sessionId,
    ): PersistentShell {
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
            return existing
        }

        return globalLock.withLock {
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck
            }

            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(fsSessionId)
            val shell = PersistentShell(appContext, chatSessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            shell
        }
    }

    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val built = SessionMounts.forContext(appContext, sessionId)
        PRootKernel.noteLegacySession(appContext, sessionId)
        Log.i(TAG, "[$sessionId] private bind mounts=${built.mounts.size} skipped=${built.skipped}")
        return built.mounts
    }

    private fun removeSessionShells(sessionId: String): PersistentShell? {
        val prefix = "$sessionId#"
        var first: PersistentShell? = null
        for (key in shells.keys.toList()) {
            if (key != sessionId && !key.startsWith(prefix)) continue
            val shell = shells.remove(key) ?: continue
            lastInjectedKeys.remove(key)
            if (first == null) first = shell else shell.stop()
        }
        lastInjectedKeys.remove(sessionId)
        return first
    }

    fun sessionDidTerminate(sessionId: String) {
        bumpStopEpoch(sessionId)
        val shell = removeSessionShells(sessionId)
        mutexes.remove(sessionId)
        lastInjectedKeys.remove(sessionId)
        mountedSessionIds.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
        val stoppedFresh = stopFreshShells(sessionId)
        if (stoppedFresh > 0) Log.i(TAG, "[$sessionId] $stoppedFresh fresh-process command(s) killed on terminate")
    }

    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) bumpStopEpoch(sessionId)
        if (sessionId != null) {
            val shell = removeSessionShells(sessionId)
            shell?.stop()
            val fresh = stopFreshShells(sessionId)
            com.openminis.app.logging.AppLogger.info(
                TAG,
                "[StopLate] [$sessionId] stopped by user, command killed: " +
                    "persistentShell=${shell != null} freshCommands=$fresh",
            )
        } else {
            shells.values.forEach { it.stop() }
            shells.clear()
            ArrayList(freshShells.keys).forEach { stopFreshShells(it) }
            lastInjectedKeys.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    fun interruptForegroundCommands(sessionId: String): Int {
        bumpStopEpoch(sessionId)
        val prefix = "$sessionId#"
        var pooled = 0
        for ((key, shell) in shells.entries.toList()) {
            if (key != sessionId && !key.startsWith(prefix)) continue
            if (shell.interruptForeground()) pooled++
        }
        val fresh = stopFreshShells(sessionId)
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "[stop-tool] [$sessionId] foreground commands interrupted, pool kept: " +
                "pooled=$pooled freshCommands=$fresh",
        )
        return pooled + fresh
    }

    private val stopEpochs = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun stopEpoch(sessionId: String): Long = stopEpochs[sessionId]?.get() ?: 0L

    fun stoppedSince(sessionId: String, epoch: Long): Boolean = stopEpoch(sessionId) != epoch

    private fun bumpStopEpoch(sessionId: String) {
        stopEpochs.computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
    }

    private class StoppedBeforeStart : kotlinx.coroutines.CancellationException("stopped by the user before the command started")

    private fun notStarted(sessionId: String, phase: String): CommandResult {
        com.openminis.app.logging.AppLogger.info(TAG, "[stop-tool] [$sessionId] command stopped $phase - not run")
        return CommandResult(output = NOT_STARTED_OUTPUT, exitCode = FreshProcessShell.CANCELLED_EXIT, durationMs = 0L)
    }

    const val NOT_STARTED_OUTPUT = "[Cancelled] Stopped by the user before the command started; nothing was run."

    private const val STOP_WATCH_MS = 200L

    private fun stopFreshShells(sessionId: String): Int {
        val snapshot = freshShells[sessionId]?.let { ArrayList(it) } ?: return 0
        snapshot.forEach { runCatching { it.stop() } }
        return snapshot.size
    }

    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    suspend fun broadcastProxyChange(cleared: Set<String> = emptySet()) {
        if (!PRootKernel.isBooted) return
        val env = cleared.associateWith { "" } +
            PRootKernel.updateProxy(appContext) +
            com.openminis.app.network.NetworkShellEnvironment.proxyVariables()
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }
}
