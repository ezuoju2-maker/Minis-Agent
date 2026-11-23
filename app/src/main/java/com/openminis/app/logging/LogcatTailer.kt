package com.openminis.app.logging

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

internal class LogcatTailer(
    private val minPriority: Char = 'V',
    private val sink: (String) -> Unit,
) {

    private var process: java.lang.Process? = null
    private var thread: Thread? = null
    @Volatile private var stopping: Boolean = false

    @Synchronized
    fun start() {
        if (process != null) return
        stopping = false
        try {
            val pid = android.os.Process.myPid().toString()
            val pb = ProcessBuilder(
                "logcat", "-v", "time", "-T", "1", "--pid=$pid",
                "View:S", "ViewRootImpl:S", "BLASTBufferQueue_Java:S", "*:$minPriority",
            ).redirectErrorStream(true)
            val p = pb.start()
            process = p
            val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            thread = Thread({
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (stopping) break
                        val l = line ?: continue
                        if (l.startsWith("---------")) continue
                        try { sink(l) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                } finally {
                    try { reader.close() } catch (_: Throwable) {}
                }
            }, "LogcatTailer").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.w("LogcatTailer", "Failed to spawn logcat: ${e.message}")
            process = null
            thread = null
        }
    }

    @Synchronized
    fun stop() {
        stopping = true
        try { process?.destroy() } catch (_: Throwable) {}
        process = null
        thread = null
    }
}
