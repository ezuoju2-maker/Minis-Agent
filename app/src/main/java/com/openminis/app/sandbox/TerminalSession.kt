package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

class TerminalSession(private val context: Context) {

    companion object {
        internal fun terminalEnvironment(system: Map<String, String>, user: Map<String, String>): Map<String, String> =
            LinkedHashMap(system).apply { putAll(user) }

        private const val TAG = "TerminalSession"
        private const val READ_BUFFER_SIZE = 8192
        private const val CHUNK_SIZE = 2048
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24

        private val liveSessions = CopyOnWriteArrayList<WeakReference<TerminalSession>>()

        fun broadcastTimezone(tz: String) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyTimezone(tz)
            }
            liveSessions.removeAll(dead)
        }

        fun broadcastProxy(env: Map<String, String>) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyEnvMap(env)
            }
            liveSessions.removeAll(dead)
        }
    }

    enum class State { IDLE, BOOTING, RUNNING, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _outputBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val outputBytes: SharedFlow<ByteArray> = _outputBytes.asSharedFlow()

    private val _clearVersion = MutableStateFlow(0)
    val clearVersion: StateFlow<Int> = _clearVersion.asStateFlow()

    private var masterFd: Int = -1
    private var childPid: Int = 0
    private var readerJob: Job? = null

    @Volatile
    private var useNoSeccomp = false

    @Volatile
    private var sawOutput = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cols: Int = DEFAULT_COLS
    @Volatile private var rows: Int = DEFAULT_ROWS

    val isRunning: Boolean get() = _state.value == State.RUNNING

    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        if (_state.value == State.RUNNING) return
        _state.value = State.BOOTING
        cols = initialCols
        rows = initialRows
        sawOutput = false

        scope.launch {
            try {
                PRootKernel.boot(context)


                val cmdList = buildInteractiveCommand(sessionId)
                val cmd = cmdList.first()
                val argv = cmdList.toTypedArray()

                val envMap = LinkedHashMap<String, String>()
                envMap["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
                if (PRootKernel.nativeLibDir.isNotEmpty())
                    envMap["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
                if (PRootKernel.prootLoaderPath.isNotEmpty())
                    envMap["PROOT_LOADER"] = PRootKernel.prootLoaderPath
                if (PRootKernel.prootLoader32Path.isNotEmpty())
                    envMap["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
                envMap["TERM"] = "xterm-256color"
                envMap["LANG"] = "C.UTF-8"
                envMap["LC_ALL"] = "C.UTF-8"
                envMap["TZ"] = PRootKernel.posixTz()
                envMap.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())
                envMap.putAll(
                    terminalEnvironment(
                        PRootKernel.customEnvironment,
                        ExecutionCoordinator.envVarRepository?.allAsDict().orEmpty(),
                    )
                )

                if (useNoSeccomp) {
                    envMap[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
                }

                val envp = envMap.map { (k, v) -> "$k=$v" }.toTypedArray()
                val cwd = File(cmd).parentFile?.absolutePath

                val outPid = IntArray(1)
                val fd = PtyBridge.forkExec(cmd, argv, envp, cwd, cols, rows, outPid)
                if (fd < 0) {
                    Log.e(TAG, "forkExec failed: errno=${-fd}")
                    _outputBytes.emit("Failed to start PTY: errno=${-fd}\r\n".toByteArray())
                    _state.value = State.STOPPED
                    return@launch
                }
                masterFd = fd
                childPid = outPid[0]
                _state.value = State.RUNNING
                liveSessions.add(WeakReference(this@TerminalSession))
                Log.i(TAG, "PTY started: fd=$fd pid=$childPid cols=$cols rows=$rows")

                if (sessionId != null) {
                    kotlinx.coroutines.delay(300)
                    writeInput("cd /var/minis && clear\n".toByteArray())
                }
                scope.launch {
                    kotlinx.coroutines.delay(if (sessionId != null) 600 else 300)
                    _outputBytes.emit(banner(sessionId).toByteArray())
                }

                readerJob = scope.launch { readLoop() }

                val spawnedAt = System.currentTimeMillis()
                scope.launch {
                    val status = PtyBridge.waitFor(childPid)
                    Log.i(TAG, "Child exited: status=$status")

                    val aliveMs = System.currentTimeMillis() - spawnedAt
                    if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                            exitCode = if (status < 0) -status else null,
                            durationMs = aliveMs,
                            producedOutput = sawOutput,
                            alreadyRetried = useNoSeccomp,
                        )
                    ) {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            SeccompFallbackPolicy.retryLogLine(-status, aliveMs, "interactive terminal"),
                        )
                        useNoSeccomp = true
                        masterFd = -1
                        childPid = 0
                        readerJob?.cancel()
                        readerJob = null
                        start(sessionId, cols, rows)
                        return@launch
                    }

                    _outputBytes.emit("\r\n[Process exited: $status]\r\n".toByteArray())
                    _state.value = State.STOPPED
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start PTY session", t)
                _outputBytes.emit("Error: ${t.message}\r\n".toByteArray())
                _state.value = State.STOPPED
            }
        }
    }

    private suspend fun readLoop() {
        val fd = masterFd
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (_state.value == State.RUNNING) {
            val n = PtyBridge.readBytes(fd, buf, 0, buf.size)
            if (n <= 0) {
                if (n < 0) Log.d(TAG, "readBytes returned errno=${-n}")
                break
            }
            sawOutput = true
            _outputBytes.emit(buf.copyOf(n))
        }
    }

    fun sendRawBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || masterFd < 0) return
        scope.launch { writeInput(bytes) }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        val normalized = normalizeLineEndings(text)
        sendRawBytes(normalized.toByteArray(Charsets.UTF_8))
    }

    private fun normalizeLineEndings(text: String): String {
        if ('\n' !in text && '\r' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when (c) {
                '\r' -> {
                    sb.append('\r')
                    if (i + 1 < n && text[i + 1] == '\n') i++
                }
                '\n' -> sb.append('\r')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) {
        sendRawBytes((normalizeLineEndings(text) + "\r").toByteArray(Charsets.UTF_8))
    }

    fun sendInterrupt() {
        sendRawBytes(byteArrayOf(0x03))
    }

    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        if (masterFd >= 0) {
            val rc = PtyBridge.setWindowSize(masterFd, newCols, newRows)
            if (rc < 0) Log.w(TAG, "setWindowSize failed: errno=${-rc}")
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        val fd = masterFd
        val pid = childPid
        masterFd = -1
        childPid = 0
        if (fd >= 0) PtyBridge.closeFd(fd)
        if (pid > 0) PtyBridge.sendSignal(pid, 15)
        if (_state.value != State.STOPPED) _state.value = State.STOPPED
        liveSessions.removeAll { it.get() === this || it.get() == null }
    }

    private fun applyTimezone(tz: String) {
        if (masterFd < 0) return
        val line = "export TZ='${tz.replace("'", "'\\''")}'\r"
        sendRawBytes(line.toByteArray(Charsets.UTF_8))
    }

    private fun applyEnvMap(env: Map<String, String>) {
        if (masterFd < 0 || env.isEmpty()) return
        val sb = StringBuilder()
        for ((k, v) in env) {
            val escaped = v.replace("'", "'\\''")
            sb.append("export ").append(k).append("='").append(escaped).append("'\r")
        }
        sendRawBytes(sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun clearOutput() {
        _clearVersion.value = _clearVersion.value + 1
    }

    private suspend fun writeInput(bytes: ByteArray) {
        val fd = masterFd
        if (fd < 0) return
        var off = 0
        while (off < bytes.size) {
            val take = minOf(CHUNK_SIZE, bytes.size - off)
            val rc = PtyBridge.writeBytes(fd, bytes, off, take)
            if (rc < 0) {
                Log.w(TAG, "writeBytes failed: errno=${-rc}")
                return
            }
            if (rc == 0) {
                Log.w(TAG, "writeBytes returned 0 at off=$off size=${bytes.size}")
                return
            }
            off += rc
            if (off < bytes.size) kotlinx.coroutines.yield()
        }
    }

    private fun banner(sessionId: String?): String =
        if (sessionId != null) {
            "\u001b[2m[Minis] /var/minis/{workspace,attachments,offloads,browser} are this chat's " +
                "(session ${sessionId.take(8)}) — the agent in that chat sees the same files.\u001b[0m\r\n"
        } else {
            "\u001b[2m[Minis] Global terminal — not tied to any chat. /var/minis/workspace here is NOT " +
                "any chat's workspace; open the terminal from a chat's menu to work in its files. " +
                "Shared: /var/minis/{shared,memory,skills}.\u001b[0m\r\n"
        }

    private fun buildInteractiveCommand(sessionId: String?): List<String> {
        check(PRootKernel.isBooted) { "PRootKernel must be booted" }
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

        cmd.addAll(SessionMounts.toProotArgs(SessionMounts.forContext(context, sessionId).mounts))

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        cmd.add("--fake-netlink")
        cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))

        cmd.add("/bin/sh")
        cmd.add("-l")
        cmd.add("-i")
        return cmd
    }
}
