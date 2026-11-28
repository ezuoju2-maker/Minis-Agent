package com.openminis.app.browser.lightpanda

import android.content.Context
import com.openminis.app.network.NetworkShellEnvironment
import com.openminis.app.network.ShellTransparentNet
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.SpawnSignalMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class LightPandaServer private constructor(private val context: Context) {

    companion object {
        private const val START_TIMEOUT_MS = 30_000L
        private const val TAG = "LightPandaServer"

        @Volatile
        private var instance: LightPandaServer? = null

        fun getInstance(context: Context): LightPandaServer {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return LightPandaServer(context.applicationContext).also { instance = it }
            }
        }

        fun installedVersion(context: Context): String =
            if (LightPandaInstaller.isInstalled(context)) LightPandaInstaller.VERSION else ""
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var lastError: String = ""

    private val logFile: File get() = File(context.cacheDir, "lightpanda.log")

    val isRunning: Boolean
        get() = process?.isAlive == true && port > 0

    fun statusLine(): String {
        val installed = installedVersion(context)
        val state = when {
            installed.isEmpty() -> "not installed"
            isRunning -> "running on 127.0.0.1:$port"
            else -> "stopped"
        }
        val err = lastError.takeIf { it.isNotEmpty() }?.let { " (last error: $it)" } ?: ""
        return "Lightpanda $installed: $state$err"
    }

    suspend fun ensureRunning(): Result<String> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (isRunning && probe(port)) {
                lastError = ""
                return@withContext Result.success("ws://127.0.0.1:$port/")
            }
            stopLocked()
            val installError = LightPandaInstaller.installIfNeeded(context)
            if (installError != null) {
                lastError = installError
                return@withContext Result.failure(IllegalStateException(installError))
            }
            val freePort = runCatching {
                ServerSocket(0).use { it.localPort }
            }.getOrElse { 9222 }
            val started = runCatching { spawn(freePort) }
            if (started.isFailure) {
                val msg = started.exceptionOrNull()?.message ?: "spawn failed"
                lastError = msg
                return@withContext Result.failure(IllegalStateException(msg))
            }
            process = started.getOrThrow()
            port = freePort
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (process?.isAlive != true) break
                if (probe(freePort)) {
                    lastError = ""
                    return@withContext Result.success("ws://127.0.0.1:$freePort/")
                }
                delay(250)
            }
            val log = runCatching { logFile.readText().takeLast(1200) }.getOrDefault("")
            lastError = if (log.isBlank()) "did not answer on 127.0.0.1:$freePort" else log.trim()
            stopLocked()
            Result.failure(IllegalStateException(lastError))
        }
    }

    private fun probe(p: Int): Boolean = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$p/json/version").get().build()
        http.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun version(): String = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$port/json/version").get().build()
        http.newCall(req).execute().use { body ->
            if (!body.isSuccessful) return@use ""
            body.body?.string() ?: ""
        }
    }.getOrDefault("")

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        val p = process
        process = null
        port = 0
        if (p != null) {
            runCatching { p.destroy() }
            runCatching { p.waitFor(3, TimeUnit.SECONDS) }
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
    }

    private fun spawn(p: Int): Process {
        val rootfs = RootfsManager.getInstance(context)
        logFile.parentFile?.mkdirs()
        runCatching { logFile.writeText("") }

        val cmd = mutableListOf<String>()
        cmd.add(rootfs.prootBinary.absolutePath)
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfs.rootfsDir.absolutePath)
        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/sys")
        cmd.add("-w")
        cmd.add("/root")
        cmd.add("--fake-netlink")
        cmd.addAll(ShellTransparentNet.prootArgs(rootfs.prootBinary))
        cmd.add(LightPandaInstaller.GUEST_PATH)
        cmd.add("serve")
        cmd.add("--host")
        cmd.add("127.0.0.1")
        cmd.add("--port")
        cmd.add(p.toString())
        cmd.add("--log-level")
        cmd.add("warn")

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        env["TZ"] = PRootKernel.posixTz()
        env.putAll(NetworkShellEnvironment.proxyVariables())
        return SpawnSignalMask.start(pb)
    }
}
