package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal class FreshProcessShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,
    private val useNoSeccomp: Boolean = false,
) {

    suspend fun execute(
        command: String,
        timeout: Long,
        envVars: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        val exitDir = File(RootfsManager.getInstance(context).rootfsDir, EXIT_DIR_NAME)
        runCatching { exitDir.mkdirs(); pruneStaleExitFiles(exitDir) }
        val token = java.util.UUID.randomUUID().toString()
        val statusFile = File(exitDir, token)
        statusHandle = statusFile
        val process = try {
            spawn(command, envVars, "/$EXIT_DIR_NAME/$token")
        } catch (e: Exception) {
            Log.e(TAG, "[$sessionId] spawn failed: ${e.message}")
            return@withContext Pair("$SPAWN_FAILED_PREFIX ${e.message}]", -1)
        }
        live = process
        if (stopped) {
            runCatching { process.destroyForcibly() }
            live = null
            return@withContext Pair("[Cancelled]", CANCELLED_EXIT)
        }

        val output = StringBuilder()

        val stderrDrain = if (SEPARATE_STDERR) {
            StderrDrain(process.errorStream) { Log.d("PRootStderr", it) }.start()
        } else {
            null
        }

        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val readEof = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(timeout)
            run {
                timedOut.set(true)
                killCommand(process, readEof)
                Log.i(TAG, "[$sessionId] timeout after ${timeout / 1000}s, command killed")
            }
        }

        val lastReadAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val closedOnStatus = java.util.concurrent.atomic.AtomicBoolean(false)
        val monitor = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            while (!statusFile.exists()) {
                if (readEof.get()) return@launch
                kotlinx.coroutines.delay(STATUS_POLL_MS)
            }
            val seenAt = System.currentTimeMillis()
            while (!readEof.get()) {
                val now = System.currentTimeMillis()
                if (outputSettled(now, seenAt, lastReadAt.get())) {
                    closedOnStatus.set(true)
                    runCatching { process.inputStream.close() }
                    Log.i(TAG, "[$sessionId] command exited; proot still tracing a detached process, left running")
                    return@launch
                }
                kotlinx.coroutines.delay(STATUS_POLL_MS)
            }
        }

        val exitCode = try {
            val buf = CharArray(8192)
            val stream = java.io.InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                lastReadAt.set(System.currentTimeMillis())
                val text = String(buf, 0, n)
                output.append(text)
                lineCallback?.let { feedLines(text, it) }
            }
            readEof.set(true)
            resolveExitCode(process, statusFile, closedOnStatus.get())
        } catch (e: Exception) {
            readEof.set(true)
            if (!timedOut.get() && !closedOnStatus.get() && !stopped) {
                Log.w(TAG, "[$sessionId] read failed: ${e.message}")
            }
            resolveExitCode(process, statusFile, closedOnStatus.get())
        } finally {
            watchdog.cancel()
            monitor.cancel()
        }

        val stderrWait = if (closedOnStatus.get()) STDERR_SETTLE_MS else STDERR_DRAIN_WAIT_MS
        if (closedOnStatus.get()) runCatching { process.outputStream.close() }
        stderrDrain?.finish(stderrWait)?.takeIf { it.isNotEmpty() }?.let { err ->
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            output.append(err)
        }

        live = null
        statusHandle = null
        runCatching { statusFile.delete() }
        runCatching { File(statusFile.path + ".pid").delete() }

        if (stopped) {
            val partial = output.toString()
            val notice = "[Cancelled]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                CANCELLED_EXIT,
            )
        }

        if (timedOut.get()) {
            val partial = output.toString()
            val notice = "[Command timed out after ${timeout / 1000}s]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                124,
            )
        }

        Pair(output.toString(), exitCode)
    }

    private fun spawn(command: String, envVars: Map<String, String>, statusGuestPath: String): Process {
        val rootfsManager = RootfsManager.getInstance(context)
        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-r"); cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b"); cmd.add("$hostPath:$linuxPath")
        }
        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }
        cmd.add("--fake-netlink")
        cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))
        cmd.addAll(ForegroundCommandGroup.wrapForFreshProcess(command, statusGuestPath))

        val debugOffload = com.openminis.app.BuildConfig.DEV_TOOLS
        val pb = ProcessBuilder(cmd)
        configureStdin(pb)
        pb.redirectErrorStream(!SEPARATE_STDERR)

        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        env["TERM"] = "dumb"
        env["PS1"] = ""
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        env["MINIS_CHAT_SESSION_ID"] = sessionId
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        env.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())
        for ((k, v) in envVars) env[k] = v
        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }
        return SpawnSignalMask.start(pb)
    }

    fun stop() {
        stopped = true
        val p = live ?: return
        kotlin.concurrent.thread(name = "FreshShellStop", isDaemon = true) {
            killCommand(p, readEof = null)
            Log.i(TAG, "[$sessionId] stopped by user, command killed")
        }
    }

    @Volatile private var live: Process? = null

    @Volatile private var statusHandle: File? = null

    @Volatile private var stopped: Boolean = false

    private fun resolveExitCode(process: Process, statusFile: File, closedOnStatus: Boolean): Int {
        if (closedOnStatus) return readStatus(statusFile) ?: 0
        val exited = runCatching {
            process.waitFor(REAP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        val processExit = if (exited) runCatching { process.exitValue() }.getOrNull() else null
        return readStatus(statusFile) ?: processExit ?: 0
    }

    private fun readStatus(statusFile: File): Int? {
        repeat(3) {
            val v = runCatching { statusFile.readText().trim().toIntOrNull() }.getOrNull()
            if (v != null) return v
            if (!statusFile.exists()) return null
            Thread.sleep(10)
        }
        return null
    }

    private fun killCommand(process: Process, readEof: java.util.concurrent.atomic.AtomicBoolean?) {
        val pgid = statusHandle?.let { readPgid(File(it.path + ".pid")) }
        if (pgid == null) {
            killProcessTree(process)
            runCatching { process.destroyForcibly() }
        } else {
            runCatching { android.system.Os.kill(-pgid, android.system.OsConstants.SIGKILL) }
            val deadline = System.currentTimeMillis() + KILL_SETTLE_MS
            while (readEof?.get() != true && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(STATUS_POLL_MS) }
            }
        }
        // Belt and braces: if anything still holds the write end, the read
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun readPgid(pidFile: File): Int? {
        val v = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull() ?: return null
        return if (v > 1) v else null
    }

    private fun pruneStaleExitFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_EXIT_FILE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun killProcessTree(process: Process) {
        val pid: Int = runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(process)
        }.getOrNull() ?: return
        runCatching { android.os.Process.sendSignal(-pid, 9) }
        for (child in descendantsOf(pid)) {
            runCatching { android.os.Process.sendSignal(child, 9) }
        }
    }

    private fun descendantsOf(root: Int, depth: Int = 0): List<Int> {
        if (depth > MAX_TREE_DEPTH) return emptyList()
        val out = mutableListOf<Int>()
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return out
        for (e in entries) {
            val childPid = e.name.toIntOrNull() ?: continue
            val ppid = runCatching {
                val raw = File(e, "stat").readText()
                raw.substring(raw.lastIndexOf(')') + 2).split(' ')[1].toInt()
            }.getOrNull() ?: continue
            if (ppid == root) {
                out += descendantsOf(childPid, depth + 1)
                out += childPid
            }
        }
        return out
    }

    private var lineBuffer = StringBuilder()

    private fun feedLines(text: String, callback: (String) -> Unit) {
        lineBuffer.append(text)
        var idx = lineBuffer.indexOf("\n")
        while (idx >= 0) {
            callback(lineBuffer.substring(0, idx))
            lineBuffer.delete(0, idx + 1)
            idx = lineBuffer.indexOf("\n")
        }
    }

    companion object {
        const val SPAWN_FAILED_PREFIX = "[Failed to start shell:"

        internal fun configureStdin(pb: ProcessBuilder): ProcessBuilder =
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        private const val TAG = "FreshProcessShell"

        private val SEPARATE_STDERR = com.openminis.app.BuildConfig.DEV_TOOLS

        private const val STDERR_DRAIN_WAIT_MS = 2_000L

        private const val MAX_TREE_DEPTH = 8

        private const val REAP_GRACE_MS = 2_000L

        const val EXIT_DIR_NAME = ".minis-exit"

        const val STATUS_POLL_MS = 25L

        const val OUTPUT_QUIET_MS = 150L

        const val OUTPUT_DRAIN_CAP_MS = 2_000L

        private const val STDERR_SETTLE_MS = 200L

        private const val KILL_SETTLE_MS = 1_000L

        private const val STALE_EXIT_FILE_MS = 60 * 60_000L

        fun outputSettled(now: Long, statusSeenAt: Long, lastReadAt: Long): Boolean {
            if (now - statusSeenAt >= OUTPUT_DRAIN_CAP_MS) return true
            return now - statusSeenAt >= OUTPUT_QUIET_MS && now - lastReadAt >= OUTPUT_QUIET_MS
        }

        const val CANCELLED_EXIT = 130
    }
}
