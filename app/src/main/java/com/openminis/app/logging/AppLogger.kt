package com.openminis.app.logging

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "AppLogger"
    private const val LOG_DIR = "logs"
    private const val MAX_AGE_DAYS = 15
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private const val PREF_NAME = "logging_prefs"
    private const val KEY_ENABLED = "logging_enabled"

    private const val KEY_VERBOSE = "logging_verbose"
    @Volatile
    private var verbose: Boolean = false

    @androidx.annotation.VisibleForTesting
    internal var MAX_LOG_FILE_BYTES = 500L * 1024 * 1024
    @androidx.annotation.VisibleForTesting
    internal var SIZE_CHECK_EVERY_BYTES = 1L * 1024 * 1024
    private var bytesSinceSizeCheck = 0L

    private var logDir: File? = null

    @Volatile
    private var appContext: Context? = null

    fun primeContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun resolveLogDir(): File? =
        logDir ?: appContext?.let { File(it.filesDir, LOG_DIR) }

    private var currentDate: String = ""
    private var writer: PrintWriter? = null
    private var enabled: Boolean = false

    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var captureActive: Boolean = false

    private var logcatTailer: LogcatTailer? = null

    fun init(context: Context) {
        primeContext(context)
        logDir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        verbose = prefs.getBoolean(KEY_VERBOSE, false)
        pruneOldLogs()
        if (enabled) startCapture()
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun isVerbose(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERBOSE, false)

    @Synchronized
    fun setVerbose(context: Context, value: Boolean) {
        verbose = value
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VERBOSE, value).apply()
        if (captureActive) {
            logcatTailer?.stop()
            logcatTailer = newLogcatTailer().also { it.start() }
        }
    }

    private fun newLogcatTailer() =
        LogcatTailer(minPriority = if (verbose) 'V' else 'I') { line -> writeLogcatLine(line) }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) startCapture() else stopCapture()
    }

    @Synchronized
    private fun startCapture() {
        if (captureActive) return
        if (logDir == null) return
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err
        System.setOut(PrintStream(LineCapturingStream(originalOut!!, "STDOUT"), true))
        System.setErr(PrintStream(LineCapturingStream(originalErr!!, "STDERR"), true))
        logcatTailer = newLogcatTailer().also { it.start() }
        captureActive = true
        info("AppLogger", "Logging session started — capturing stdout/stderr + logcat tail")
        appContext?.let { com.openminis.app.diagnostics.EnvironmentBanner.log(it) }
        com.openminis.app.diagnostics.JankDiag.onLoggingStarted()
    }

    @Synchronized
    private fun stopCapture() {
        if (!captureActive) return
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        logcatTailer?.stop()
        logcatTailer = null
        captureActive = false
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        currentDate = ""
    }

    private fun writeLogcatLine(rawLine: String) {
        if (!enabled) return
        val slashIdx = rawLine.indexOf('/')
        val parenIdx = if (slashIdx >= 0) rawLine.indexOf('(', slashIdx) else -1
        if (slashIdx >= 0 && parenIdx > slashIdx) {
            val tag = rawLine.substring(slashIdx + 1, parenIdx).trim()
            if (tag.startsWith("Minis.") || tag == "AppLogger" ||
                tag == "System.out" || tag == "System.err"
            ) {
                return
            }
        }
        try {
            appendLine(dateFormat.format(Date()), "[LOGCAT] $rawLine")
        } catch (_: Exception) {
        }
    }

    private class LineCapturingStream(
        private val delegate: PrintStream,
        private val tag: String,
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            delegate.write(b)
            if (b == '\n'.code) {
                emitLine()
            } else {
                buffer.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            var lineStart = off
            val end = off + len
            for (i in off until end) {
                if (b[i] == '\n'.code.toByte()) {
                    if (i > lineStart) buffer.write(b, lineStart, i - lineStart)
                    emitLine()
                    lineStart = i + 1
                }
            }
            if (lineStart < end) buffer.write(b, lineStart, end - lineStart)
        }

        override fun flush() {
            delegate.flush()
        }

        private fun emitLine() {
            val line = try {
                buffer.toString("UTF-8")
            } catch (_: Exception) {
                buffer.toString()
            }
            buffer.reset()
            if (line.isEmpty()) return
            writeFileLine(tag, line)
        }
    }

    @Synchronized
    private fun writeFileLine(channel: String, line: String) {
        if (!enabled) return
        try {
            val now = Date()
            appendLine(dateFormat.format(now), "[${timestampFormat.format(now)}] [$channel] $line")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write captured line: ${e.message}")
        }
    }

    fun info(category: String, message: String) {
        log("INFO", category, message)
    }

    fun warning(category: String, message: String) {
        log("WARN", category, message)
    }

    fun error(category: String, message: String) {
        log("ERROR", category, message)
    }

    private val mutedDebugCategories = setOf("ChatScrollFollow", "ScrollSrc")

    fun debug(category: String, message: String) {
        if (category in mutedDebugCategories) return
        log("DEBUG", category, message)
    }

    inline fun debug(category: String, message: () -> String) {
        if (isDebugMuted(category)) return
        debug(category, message())
    }

    fun isDebugMuted(category: String): Boolean = category in mutedDebugCategories

    val traceEnabled: Boolean
        get() = com.openminis.app.BuildConfig.DEBUG || (enabled && verbose)

    inline fun trace(category: String, message: () -> String) {
        if (!traceEnabled || isDebugMuted(category)) return
        debug(category, message())
    }

    private fun log(level: String, category: String, message: String) {
        val logcatTag = "Minis.$category"
        when (level) {
            "ERROR" -> Log.e(logcatTag, message)
            "WARN" -> Log.w(logcatTag, message)
            "DEBUG" -> Log.d(logcatTag, message)
            else -> Log.i(logcatTag, message)
        }

        if (!enabled) return
        if (level == "DEBUG" && !verbose) return
        val onMain = android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
        val t0 = if (onMain) System.nanoTime() else 0L
        val now = Date()
        try {
            appendLine(dateFormat.format(now), "[${timestampFormat.format(now)}] [$level] [$category] $message")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write log: ${e.message}")
        }
        if (onMain) {
            val d = System.nanoTime() - t0
            statMainCalls.incrementAndGet()
            statMainNs.addAndGet(d)
            if (d > statMainMaxNs) statMainMaxNs = d
        }
    }

    class Stats(val lines: Long, val mainCalls: Long, val mainNs: Long, val mainMaxNs: Long)

    private val statLines = java.util.concurrent.atomic.AtomicLong()
    private val statMainCalls = java.util.concurrent.atomic.AtomicLong()
    private val statMainNs = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var statMainMaxNs = 0L

    fun drainStats(): Stats {
        val out = Stats(statLines.getAndSet(0), statMainCalls.getAndSet(0), statMainNs.getAndSet(0), statMainMaxNs)
        statMainMaxNs = 0L
        return out
    }

    @Synchronized
    private fun appendLine(date: String, text: String) {
        statLines.incrementAndGet()
        val w = getWriter(date)
        w.println(text)
        w.flush()
        bytesSinceSizeCheck += text.length + 1
        if (bytesSinceSizeCheck >= SIZE_CHECK_EVERY_BYTES) {
            bytesSinceSizeCheck = 0
            truncateIfNeeded(date)
        }
    }

    @Synchronized
    private fun truncateIfNeeded(date: String) {
        val dir = logDir ?: return
        val file = File(dir, "minis-$date.log")
        val size = file.length()
        if (size <= MAX_LOG_FILE_BYTES) return
        writer?.close()
        writer = null
        currentDate = ""
        val tmp = File(dir, "minis-$date.log.truncating")
        try {
            java.io.FileInputStream(file).channel.use { src ->
                java.io.FileOutputStream(tmp, false).use { out ->
                    out.write("[log truncated: kept the newest ${MAX_LOG_FILE_BYTES / 2 / (1024 * 1024)} MB]\n".toByteArray())
                    val start = size - MAX_LOG_FILE_BYTES / 2
                    var pos = start
                    while (pos < size) {
                        val n = src.transferTo(pos, size - pos, out.channel)
                        if (n <= 0) break
                        pos += n
                    }
                }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "log truncate failed: ${e.message}")
        }
    }

    @Synchronized
    private fun getWriter(date: String): PrintWriter {
        if (date != currentDate || writer == null) {
            writer?.close()
            val dir = logDir ?: throw IllegalStateException("AppLogger not initialized")
            val file = File(dir, "minis-$date.log")
            writer = PrintWriter(FileWriter(file, true))
            currentDate = date
        }
        return writer!!
    }

    fun listLogFiles(): List<File> {
        val dir = resolveLogDir() ?: return emptyList()
        return dir.listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    data class LogFileMeta(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    fun listLogFileMetas(prefix: String, limit: Int): List<LogFileMeta> {
        val dir = resolveLogDir() ?: return emptyList()
        val files = dir.listFiles { f ->
            f.extension == "log" && (prefix.isEmpty() || f.name.startsWith(prefix))
        } ?: return emptyList()
        return files
            .sortedByDescending { it.name }
            .take(limit)
            .map { LogFileMeta(it.name, it.length(), it.lastModified()) }
    }

    fun readLog(filename: String): String? {
        val dir = resolveLogDir() ?: return null
        val file = File(dir, filename)
        return if (file.exists()) file.readText() else null
    }

    @Synchronized
    fun clearLogs() {
        logDir?.listFiles()?.forEach { it.delete() }
        writer?.close()
        writer = null
        currentDate = ""
    }

    fun totalSize(): Long {
        return resolveLogDir()?.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun pruneOldLogs() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        logDir?.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
