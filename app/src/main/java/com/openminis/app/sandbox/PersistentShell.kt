package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,
) {

    companion object {
        private const val TAG = "PersistentShell"

        private const val MARKER_CARRY = 96

        private const val OUTPUT_HEAD_MAX = 1024
        private const val OUTPUT_TAIL_MAX = 2048
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private val isStarting = AtomicBoolean(false)

    @Volatile
    private var pendingCallback: CommandCallback? = null

    private var carryOver: String = ""

    @Volatile
    private var setsidAvailable: Boolean = true

    private fun stripControlMarkers(text: String, marker: String): String {
        val prefix = ForegroundCommandGroup.pgidMarkerPrefix(marker)
        if (!text.contains(prefix)) return text
        return text.lineSequence()
            .filterNot { it.contains(prefix) }
            .joinToString("\n")
    }

    val isAlive: Boolean
        get() = process?.isAlive == true

    fun debugBindMount(linuxPath: String): String? = sessionBindMounts[linuxPath]

    private class CommandCallback(
        val marker: String,
        val output: StringBuilder = StringBuilder(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int) -> Unit)? = null,
        @Volatile var pgid: Int? = null,
    )

    suspend fun ensureStarted() {
        if (isAlive) return
        if (!isStarting.compareAndSet(false, true)) {
            while (isStarting.get() && !isAlive) {
                kotlinx.coroutines.delay(50)
            }
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val spawnedAt = System.currentTimeMillis()
                startProcess()
                val aliveMs = System.currentTimeMillis() - spawnedAt

                if (!useNoSeccomp && !isAlive) {
                    val exit = runCatching { process?.exitValue() }.getOrNull() ?: lastExitCode
                    if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                            exitCode = exit,
                            durationMs = aliveMs,
                            producedOutput = false,
                            alreadyRetried = false,
                        )
                    ) {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            SeccompFallbackPolicy.retryLogLine(exit, aliveMs, "persistent shell startup"),
                        )
                        useNoSeccomp = true
                        startProcess()
                        if (isAlive) {
                            com.openminis.app.logging.AppLogger.warning(
                                TAG,
                                "[proot-retry] persistent shell came up with " +
                                    "${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — this device " +
                                    "needs the seccomp workaround (GH#186)",
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            com.openminis.app.logging.AppLogger.error(
                TAG,
                "spawn threw: ${t.javaClass.simpleName}: ${t.message}",
            )
            throw t
        } finally {
            isStarting.set(false)
        }
        replayEnvironmentIfAny()
    }

    @Volatile
    private var useNoSeccomp = false

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")

        val rootfsManager = RootfsManager.getInstance(context)

        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn ctx: proot=${rootfsManager.prootBinary.exists()} " +
                "rootfs=${File(rootfsManager.rootfsDir, "bin/busybox").exists()} " +
                "loader64=${PRootKernel.prootLoaderPath.isNotEmpty()} " +
                "loader32=${PRootKernel.prootLoader32Path.isNotEmpty()}",
        )
        synchronized(outputTail) {
            outputHead.setLength(0)
            outputTail.setLength(0)
            outputTotal = 0
        }
        lastExitCode = null

        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
        }

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }
        cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))

        cmd.add("/bin/sh")

        val debugOffload = com.openminis.app.BuildConfig.DEV_TOOLS

        val processBuilder = ProcessBuilder(cmd)
        processBuilder.redirectErrorStream(!debugOffload)

        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        if (PRootKernel.prootLoaderPath.isNotEmpty()) {
            env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        }
        if (PRootKernel.prootLoader32Path.isNotEmpty()) {
            env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        }
        env["TERM"] = "dumb"
        env["PS1"] = ""
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        env["MINIS_CHAT_SESSION_ID"] = sessionId

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }
        env.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())

        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        val p = SpawnSignalMask.start(processBuilder)
        process = p
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        if (debugOffload) {
            Thread({
                val br = p.errorStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    for (line in br.lineSequence()) Log.d("PRootStderr", line)
                } catch (_: Exception) {}
            }, "PersistentShell-stderr").apply {
                isDaemon = true
                start()
            }
        }

        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {}

        val alive = isAlive
        val exit = runCatching { process?.exitValue() }.getOrNull()
        val early = synchronized(outputTail) { outputHead.toString().take(300) }
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn outcome: alive=$alive" +
                (if (exit != null) " exit=$exit" else "") +
                " loader=${PRootKernel.prootLoaderPath.isNotEmpty()}" +
                " noSeccomp=$useNoSeccomp" +
                (if (early.isNotEmpty()) " early=${early.replace('\n', '|')}" else ""),
        )
        Log.i(TAG, "Persistent shell started")
    }

    private val outputHead = StringBuilder()
    private val outputTail = StringBuilder()
    private var outputTotal = 0

    private fun appendTail(text: String) {
        synchronized(outputTail) {
            outputTotal += text.length
            if (outputHead.length < OUTPUT_HEAD_MAX) {
                outputHead.append(text.take(OUTPUT_HEAD_MAX - outputHead.length))
            }
            outputTail.append(text)
            val over = outputTail.length - OUTPUT_TAIL_MAX
            if (over > 0) outputTail.delete(0, over)
        }
    }

    fun deathTail(): String = synchronized(outputTail) {
        val head = outputHead.toString().trim()
        if (outputTotal <= OUTPUT_HEAD_MAX) return head
        val overlap = OUTPUT_HEAD_MAX - (outputTotal - outputTail.length)
        val tail = outputTail.substring(overlap.coerceIn(0, outputTail.length)).trim()
        if (tail.isEmpty()) return head
        val elided = (-overlap).coerceAtLeast(0)
        val sep = if (elided > 0) "\n…[$elided bytes elided]…\n" else "\n"
        "$head$sep$tail"
    }

    @Volatile
    var lastExitCode: Int? = null
        private set

    private fun readLoop(p: Process) {
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            val decoder = Utf8StreamDecoder()
            while (true) {
                val n = stream.read(buffer)
                val text = if (n < 0) decoder.finish() else decoder.decode(buffer, 0, n)
                if (text.isEmpty()) {
                    if (n < 0) break
                    continue
                }
                appendTail(text)

                val cb = pendingCallback
                if (cb != null) {
                    val scan = if (carryOver.isEmpty()) text else carryOver + text
                    val alreadyEmitted = carryOver.length

                    if (cb.pgid == null) {
                        ForegroundCommandGroup.parsePgid(scan, cb.marker)?.let { cb.pgid = it }
                    }

                    val markerExitPattern = "__MINIS_DONE_${cb.marker}_EXIT_"
                    val markerIdx = scan.indexOf(markerExitPattern)

                    if (markerIdx >= 0) {
                        carryOver = ""
                        val beforeMarker = scan.substring(alreadyEmitted.coerceAtMost(markerIdx), markerIdx)
                        val visible = stripControlMarkers(beforeMarker, cb.marker)
                        cb.output.append(visible)
                        if (cb.lineCallback != null) {
                            feedLines(visible, cb.lineCallback)
                        }

                        val afterMarker = scan.substring(markerIdx)
                        val exitCode = parseExitCode(afterMarker, cb.marker)

                        cb.onComplete?.invoke(cb.output.toString(), exitCode)
                        pendingCallback = null
                    } else {
                        val emitEnd = (scan.length - MARKER_CARRY).coerceAtLeast(alreadyEmitted)
                        val fresh = scan.substring(alreadyEmitted, emitEnd)
                        carryOver = scan.substring(emitEnd)
                        if (fresh.isNotEmpty()) {
                            val visible = stripControlMarkers(fresh, cb.marker)
                            cb.output.append(visible)
                            if (cb.lineCallback != null) {
                                feedLines(visible, cb.lineCallback)
                            }
                        }
                    }
                } else {
                    carryOver = ""
                }
                if (n < 0) break
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        val cb = pendingCallback
        if (cb != null) {
            cb.onComplete?.invoke(cb.output.toString(), -1)
            pendingCallback = null
        }

        val exit = runCatching { p.waitFor() }.getOrNull()
        lastExitCode = exit
        val tail = deathTail()
        com.openminis.app.logging.AppLogger.error(
            TAG,
            "shell process exited code=$exit" +
                (if (tail.isNotEmpty()) " capture=${tail.take(1200)}" else " capture=(no output)"),
        )

        process = null
        stdinWriter = null
        Log.i(TAG, "Persistent shell process exited")
    }

    private fun feedLines(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i].replace("\r", "")
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                callback(line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                callback(line)
            }
        }
    }

    private fun parseExitCode(text: String, marker: String): Int {
        val regex = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    suspend fun executeCommand(
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> {
        ensureStarted()

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            val exit = lastExitCode
            val tail = deathTail()
            val detail = buildString {
                append("[Shell not running]")
                if (exit != null) append(" proot exit=$exit")
                if (tail.isNotEmpty()) append("\n${tail.take(300)}")
            }
            return Pair(detail, -1)
        }

        val marker = UUID.randomUUID().toString().take(8)
        val wrappedCommand = ForegroundCommandGroup.wrap(command, marker, setsidAvailable)

        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { cont ->
                    val cb = CommandCallback(
                        marker = marker,
                        lineCallback = lineCallback,
                    )
                    cb.onComplete = { output, exitCode ->
                        if (cont.isActive) {
                            cont.resume(Pair(output, exitCode))
                        }
                    }
                    pendingCallback = cb

                    cont.invokeOnCancellation {
                        pendingCallback = null
                    }

                    try {
                        writer.write(wrappedCommand)
                        writer.flush()
                    } catch (e: Exception) {
                        pendingCallback = null
                        if (cont.isActive) {
                            cont.resume(Pair("[Write error: ${e.message}]", -1))
                        }
                    }
                }
            }

            if (result == null) {
                val cb = pendingCallback
                pendingCallback = null

                val partial = cb?.output?.toString().orEmpty()

                cb?.pgid?.let { pgid ->
                    runCatching {
                        stdinWriter?.let { w ->
                            w.write(ForegroundCommandGroup.killGroupCommand(pgid))
                            w.flush()
                        }
                    }.onFailure {
                        Log.w(TAG, "timeout kill for pgid=$pgid failed: ${it.message}")
                    }
                    Log.i(TAG, "[timeout] killed foreground process group $pgid")
                }

                if (!ForegroundCommandGroup.shellIsReusableAfterTimeout()) {
                    runCatching { stop() }
                        .onFailure { Log.w(TAG, "timeout recycle failed: ${it.message}") }
                }

                val notice = "[Command timed out after ${timeout / 1000}s]"
                val text = if (partial.isBlank()) notice else "$partial\n$notice"
                Pair(text, 124)
            } else {
                result
            }
        }
    }

    suspend fun applyEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        pendingEnv = envVars
        pendingEnvPreviousKeys = previousKeys
        if (!isAlive) return
        writeEnvironment(envVars, previousKeys)
    }

    private var pendingEnv: Map<String, String> = emptyMap()
    private var pendingEnvPreviousKeys: Set<String> = emptySet()

    private suspend fun writeEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String>,
    ) {
        val writer = stdinWriter ?: return
        withContext(Dispatchers.IO) {
            try {
                for (key in previousKeys - envVars.keys) {
                    writer.write("unset $key\n")
                }
                for ((key, value) in envVars) {
                    val escaped = value.replace("'", "'\\''")
                    writer.write("export $key='$escaped'\n")
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply env vars: ${e.message}")
            }
        }
    }

    private suspend fun replayEnvironmentIfAny() {
        if (pendingEnv.isEmpty()) return
        if (!isAlive) return
        writeEnvironment(pendingEnv, previousKeys = emptySet())
    }

    fun interruptForeground(): Boolean {
        val cb = pendingCallback ?: return false
        val pgid = cb.pgid
        if (pgid != null && pgid > 1) {
            val sent = runCatching {
                android.system.Os.kill(-pgid, android.system.OsConstants.SIGKILL)
            }.onFailure { Log.w(TAG, "interrupt: kill -$pgid failed: ${it.message}") }.isSuccess
            if (sent) {
                Log.i(TAG, "[stop-tool] killed foreground process group $pgid")
                return true
            }
        }
        Log.i(TAG, "[stop-tool] no process group to kill (pgid=$pgid) - recycling this shell")
        stop()
        return true
    }

    fun stop() {
        try { stdinWriter?.close() } catch (_: Exception) {}
        stdinWriter = null
        process?.destroyForcibly()
        process = null
        pendingCallback?.let {
            it.onComplete?.invoke(it.output.toString(), -1)
        }
        pendingCallback = null
        Log.i(TAG, "Persistent shell stopped")
    }
}
