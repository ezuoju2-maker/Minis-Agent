package com.openminis.app.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class NativeOffloadRequest(
    val pid: Int,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    val sessionId: String? = null,
)

data class NativeOffloadResult(
    val exitCode: Int,
    val output: String,
)

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

internal object PeerCheck {
    fun allowed(peerUid: Int, myUid: Int): Boolean = peerUid >= 0 && peerUid == myUid
}

internal fun canSetCloseOnExec(sdkInt: Int): Boolean = sdkInt >= 30

object NativeOffloadServer {
    private const val TAG = "NativeOffloadServer"
    private const val SOCKET_NAME_PREFIX = "native-offload"
    private const val MAGIC_REQ = 0x46464F4E
    private const val MAGIC_RSP = 0x52464F4E
    private const val VERSION = 1

    private const val REPLY_PREFIX = ".native-offload-"

    private const val REPLY_TTL_MS = 10 * 60 * 1000L

    private const val SWEEP_EVERY_N_REPLIES = 50L

    val socketName: String by lazy {
        socketNameFor(android.os.Process.myUid(), android.os.Process.myPid())
    }

    internal fun socketNameFor(uid: Int, pid: Int): String = "$SOCKET_NAME_PREFIX-$uid-$pid"

    private val RETRY_DELAYS_MS = longArrayOf(50L, 100L, 200L, 400L, 800L, 2_000L, 5_000L, 10_000L)
    private const val RETRY_STEADY_MS = 30_000L

    const val AWAIT_LISTENING_MS = 2_000L

    private const val AWAIT_WINDOW_MS = 5_000L

    private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()
    private val counter = AtomicLong(0)

    internal const val MAX_CONCURRENT_CALLS = 128

    internal const val EXIT_BUSY = 75

    internal val inFlight = OffloadInFlight()
    private val lastBusyLogMs = AtomicLong(0)
    @Volatile
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var retryThread: Thread? = null

    @Volatile
    private var degradedSinceMs = 0L

    val isListening: Boolean get() = serverSocket != null

    @Volatile
    private var rootfsTmpDir: File? = null

    val registeredHandlers: Set<String> get() = handlers.keys.toSet()

    fun register(name: String, handler: NativeOffloadHandler) {
        require(name.isNotEmpty())
        handlers[name] = handler
        Log.d(TAG, "register '$name' (total=${handlers.size})")
    }

    @Synchronized
    fun start(rootfsDir: File) {
        rootfsTmpDir = File(rootfsDir, "tmp")
        if (serverSocket != null || retryThread != null) return

        val s = tryBind(attempt = 1)
        if (s != null) {
            onBound(s)
            return
        }
        degradedSinceMs = android.os.SystemClock.uptimeMillis()
        Log.w(TAG, "native offload unavailable: '$socketName' is held by another process; " +
            "offload CLIs report unavailable, retrying the bind in the background")
        retryThread = thread(name = "native-offload-rebind", isDaemon = true) { retryBindLoop() }
    }

    fun awaitListening(timeoutMs: Long = AWAIT_LISTENING_MS): Boolean {
        if (serverSocket != null) return true
        if (retryThread == null) return false
        if (android.os.Looper.getMainLooper()?.isCurrentThread == true) return false
        val sinceFail = android.os.SystemClock.uptimeMillis() - degradedSinceMs
        if (sinceFail > AWAIT_WINDOW_MS) return false
        val deadline = System.currentTimeMillis() + minOf(timeoutMs, AWAIT_WINDOW_MS - sinceFail)
        while (serverSocket == null && retryThread != null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        return serverSocket != null
    }

    private fun tryBind(attempt: Int): LocalServerSocket? =
        try {
            LocalServerSocket(socketName)
        } catch (e: java.io.IOException) {
            if (attempt <= RETRY_DELAYS_MS.size + 1 || attempt % 10 == 0) {
                Log.w(TAG, "bind '$socketName' attempt $attempt failed: ${e.message}")
            }
            null
        }

    private fun setCloseOnExec(s: LocalServerSocket) {
        if (!canSetCloseOnExec(android.os.Build.VERSION.SDK_INT)) {
            Log.i(TAG, "FD_CLOEXEC on the offload socket skipped below API 30")
            return
        }
        try {
            android.system.Os.fcntlInt(s.fileDescriptor, android.system.OsConstants.F_SETFD, android.system.OsConstants.FD_CLOEXEC)
        } catch (t: Throwable) {
            Log.w(TAG, "FD_CLOEXEC on the offload socket failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun retryBindLoop() {
        var attempt = 1
        while (true) {
            val delay = RETRY_DELAYS_MS.getOrElse(attempt - 1) { RETRY_STEADY_MS }
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return
            }
            attempt++
            val s = tryBind(attempt) ?: continue
            synchronized(this) {
                retryThread = null
                degradedSinceMs = 0L
                onBound(s)
            }
            Log.i(TAG, "native offload recovered after $attempt bind attempts")
            return
        }
    }

    private fun onBound(s: LocalServerSocket) {
        setCloseOnExec(s)
        serverSocket = s
        acceptThread = thread(name = "native-offload-accept", isDaemon = true) {
            runAcceptLoop(s)
        }
        Log.i(TAG, "listening on abstract socket '$socketName' " +
            "handlers=${handlers.keys.sorted()} tmpDir=${rootfsTmpDir?.absolutePath}")

        sweepStaleReplies(all = true)
    }

    private fun sweepStaleReplies(all: Boolean) {
        val dir = rootfsTmpDir ?: return
        try {
            val cutoff = System.currentTimeMillis() - REPLY_TTL_MS
            var removed = 0
            var bytes = 0L
            dir.listFiles { f -> f.isFile && f.name.startsWith(REPLY_PREFIX) }?.forEach { f ->
                if (!all && f.lastModified() > cutoff) return@forEach
                val len = f.length()
                if (f.delete()) {
                    removed++
                    bytes += len
                }
            }
            if (removed > 0) {
                Log.i(TAG, "swept $removed stale reply file(s), freed ${bytes / 1024}KB (all=$all)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweepStaleReplies failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        retryThread?.interrupt()
        retryThread = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    private fun runAcceptLoop(s: LocalServerSocket) {
        while (true) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                Log.i(TAG, "accept loop terminated: ${e.message}")
                return
            }
            Log.d(TAG, "accepted client from proot extension")
            if (!inFlight.tryAdmit(MAX_CONCURRENT_CALLS)) {
                rejectBusy(client)
                continue
            }
            try {
                thread(name = OffloadInFlight.WORKER_THREAD_NAME, isDaemon = true) {
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        Log.w(TAG, "worker error: ${e.message}", e)
                    } finally {
                        inFlight.release()
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (t: Throwable) {
                inFlight.release()
                Log.w(TAG, "could not start an offload worker: $t")
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun rejectBusy(client: LocalSocket) {
        val busy = inFlight.summary()
        val now = System.currentTimeMillis()
        val last = lastBusyLogMs.get()
        if (now - last > 60_000 && lastBusyLogMs.compareAndSet(last, now)) {
            Log.w(TAG, "[GH453] refusing offload call: $MAX_CONCURRENT_CALLS calls in flight ($busy)")
        }
        try {
            client.soTimeout = 2_000
            val peerUid = try { client.peerCredentials.uid } catch (e: Exception) { -1 }
            if (!PeerCheck.allowed(peerUid, android.os.Process.myUid())) return
            val request = readRequest(DataInputStream(client.inputStream)) ?: return
            writeReply(
                DataOutputStream(client.outputStream),
                request.pid,
                NativeOffloadResult(
                    exitCode = EXIT_BUSY,
                    output = "native_offload: busy - $MAX_CONCURRENT_CALLS offload calls are still running ($busy). " +
                        "Try again when some finish.\n",
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "busy reply failed: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun inFlightSummary(): String = inFlight.summary()

    @Volatile
    private var loggedPeerUid = false

    private fun handleClient(client: LocalSocket) {
        val peerUid = try { client.peerCredentials.uid } catch (e: Exception) { -1 }
        if (!PeerCheck.allowed(peerUid, android.os.Process.myUid())) {
            Log.w(TAG, "refused offload client uid=$peerUid (expected ${android.os.Process.myUid()})")
            return
        }
        if (!loggedPeerUid) {
            loggedPeerUid = true
            Log.i(TAG, "offload client uid=$peerUid accepted (app uid)")
        }
        val request = readRequest(DataInputStream(client.inputStream)) ?: return
        val output = DataOutputStream(client.outputStream)
        val pid = request.pid
        val argv = request.argv
        val env = request.env
        val cwd = request.cwd

        val name = argv.firstOrNull().orEmpty().substringAfterLast('/')
        Log.d(TAG, "recv pid=$pid name='$name' argc=${argv.size} argv=$argv cwd=$cwd envc=${env.size}")

        val t0 = System.nanoTime()
        val handler = handlers[name]
        if (handler != null) {
            runCatching { Thread.currentThread().name = OffloadInFlight.workerThreadName(name) }
            inFlight.begin(name)
        }
        val result = try {
            if (handler == null) {
                Log.w(TAG, "no handler registered for '$name' (known=${handlers.keys})")
                NativeOffloadResult(exitCode = 127, output = "native_offload: no handler for '$name'\n")
            } else {
                try {
                    handler.handle(NativeOffloadRequest(
                        pid = pid,
                        argv = argv,
                        env = env,
                        cwd = cwd,
                        sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "handler '$name' threw: ${e.message}", e)
                    NativeOffloadResult(exitCode = 1, output = "native_offload: ${e.message}\n")
                }
            }
        } finally {
            if (handler != null) inFlight.end()
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val tmpGuest = writeReply(output, pid, result)
        Log.d(TAG, "reply name='$name' exit=${result.exitCode} outBytes=${result.output.length} " +
            "tmpGuest=$tmpGuest elapsed=${elapsedMs}ms")
    }

    private class WireRequest(val pid: Int, val argv: List<String>, val env: Map<String, String>, val cwd: String)

    private fun readRequest(input: DataInputStream): WireRequest? {
        val magic = input.readLEInt()
        if (magic != MAGIC_REQ) {
            Log.w(TAG, "bad magic: 0x${magic.toUInt().toString(16)}")
            return null
        }
        val version = input.readLEInt()
        if (version != VERSION) {
            Log.w(TAG, "unsupported version $version")
            return null
        }

        val pid = input.readLEInt()
        val argc = input.readLEInt()
        if (argc < 0 || argc > 256) throw IllegalStateException("bad argc=$argc")
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(input.readLEString()) }

        val envc = input.readLEInt()
        if (envc < 0 || envc > 4096) throw IllegalStateException("bad envc=$envc")
        val env = LinkedHashMap<String, String>(envc)
        repeat(envc) {
            val s = input.readLEString()
            val eq = s.indexOf('=')
            if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
        }
        val cwd = input.readLEString()
        return WireRequest(pid, argv, env, cwd)
    }

    private fun writeReply(output: DataOutputStream, pid: Int, result: NativeOffloadResult): String {
        val tmpDir = rootfsTmpDir ?: throw IllegalStateException("server not started")
        tmpDir.mkdirs()
        val seq = counter.incrementAndGet()
        val tmpHost = File(tmpDir, "$REPLY_PREFIX$pid-$seq")
        tmpHost.writeText(result.output)
        val tmpGuest = "/tmp/${tmpHost.name}"

        if (seq % SWEEP_EVERY_N_REPLIES == 0L) sweepStaleReplies(all = false)

        output.writeLEInt(MAGIC_RSP)
        output.writeLEInt(result.exitCode)
        output.writeLEString(tmpGuest)
        output.flush()
        return tmpGuest
    }


    private fun DataInputStream.readLEInt(): Int {
        val buf = ByteArray(4)
        readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun DataInputStream.readLEString(): String {
        val len = readLEInt()
        if (len < 0 || len > 1 shl 20) throw IllegalStateException("bad string len $len")
        if (len == 0) return ""
        val buf = ByteArray(len)
        readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeLEInt(v: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(buf)
    }

    private fun DataOutputStream.writeLEString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLEInt(bytes.size)
        if (bytes.isNotEmpty()) write(bytes)
    }
}
