package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object ShellExecutor {

    private const val TAG = "ShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 600_000L

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    @Volatile
    var currentProcess: Process? = null
        private set

    suspend fun execute(
        context: Context,
        command: String,
        timeout: Long = DEFAULT_TIMEOUT_MS,
        environment: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        check(PRootKernel.isBooted) { "PRootKernel must be booted before executing commands" }

        val first = runOnce(context, command, timeout, environment, lineCallback, noSeccomp = false)

        if (!SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = first.exitCode,
                durationMs = first.durationMs,
                producedOutput = first.output.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            return@withContext first
        }

        com.openminis.app.logging.AppLogger.warning(
            TAG,
            SeccompFallbackPolicy.retryLogLine(first.exitCode, first.durationMs, "shell command"),
        )
        val retried = runOnce(context, command, timeout, environment, lineCallback, noSeccomp = true)
        if (retried.exitCode == 0) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                "[proot-retry] succeeded with ${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — " +
                    "this device needs the seccomp workaround (GH#186)",
            )
        }
        retried
    }

    private suspend fun runOnce(
        context: Context,
        command: String,
        timeout: Long,
        environment: Map<String, String>,
        lineCallback: ((String) -> Unit)?,
        noSeccomp: Boolean,
    ): ShellResult = withContext(Dispatchers.IO) {
        val prootCommand = PRootKernel.buildProotCommand(command)

        Log.d(TAG, "Executing: $command")

        val startTime = System.currentTimeMillis()

        val processBuilder = ProcessBuilder(prootCommand)
        processBuilder.redirectErrorStream(true)

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

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }
        env.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())

        for ((key, value) in environment) {
            env[key] = value
        }

        if (noSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        val output = StringBuilder()
        var exitCode = -1

        try {
            withTimeout(timeout) {
                val process = SpawnSignalMask.start(processBuilder)
                currentProcess = process

                try {
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        var lastLineForCallback = StringBuilder()
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            output.append(buf, 0, n)
                            if (lineCallback != null) {
                                for (i in 0 until n) {
                                    val c = buf[i]
                                    if (c == '\n') {
                                        lineCallback.invoke(lastLineForCallback.toString())
                                        lastLineForCallback.clear()
                                    } else if (c != '\r') {
                                        lastLineForCallback.append(c)
                                    }
                                }
                            }
                        }
                        if (lineCallback != null && lastLineForCallback.isNotEmpty()) {
                            lineCallback.invoke(lastLineForCallback.toString())
                        }
                    }

                    exitCode = process.waitFor()
                } finally {
                    currentProcess = null
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Command timed out after ${timeout}ms: $command")
            currentProcess?.destroyForcibly()
            currentProcess = null
            output.appendLine("\n[Command timed out after ${timeout / 1000}s]")
            exitCode = 124
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            currentProcess?.destroyForcibly()
            currentProcess = null
            output.appendLine("\n[Error: ${e.message}]")
            exitCode = -1
        }

        val durationMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Command completed in ${durationMs}ms with exit code $exitCode")

        ShellResult(
            output = output.toString().trimEnd(),
            exitCode = exitCode,
            durationMs = durationMs
        )
    }

    fun destroyCurrent() {
        currentProcess?.let { process ->
            Log.i(TAG, "Destroying current process")
            process.destroyForcibly()
            currentProcess = null
        }
    }
}
