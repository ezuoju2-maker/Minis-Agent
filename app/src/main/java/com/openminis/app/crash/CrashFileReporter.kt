package com.openminis.app.crash

import android.content.Context
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.ReportField
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashFileSender : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        val dir = File(context.filesDir, "logs").also { it.mkdirs() }
        val stamp = STAMP_FMT.format(Date())
        val out = File(dir, "crash-$stamp.log")

        val body = buildString {
            appendLine("=== Minis Java/Kotlin Crash ===")
            appendLine("Time: $stamp")
            appendLine("Version: ${errorContent.getString(ReportField.APP_VERSION_NAME)} " +
                "(${errorContent.getString(ReportField.APP_VERSION_CODE)})")
            appendLine("Android: ${errorContent.getString(ReportField.ANDROID_VERSION)} " +
                "(SDK ${errorContent.getString(ReportField.BUILD)})")
            appendLine("Device: ${errorContent.getString(ReportField.PHONE_MODEL)} " +
                "(${errorContent.getString(ReportField.BRAND)})")
            appendLine()
            appendLine("--- Stack Trace ---")
            appendLine(errorContent.getString(ReportField.STACK_TRACE))
            val logcat = errorContent.getString(ReportField.LOGCAT)
            if (!logcat.isNullOrBlank()) {
                appendLine()
                appendLine("--- Logcat (last 200 lines) ---")
                appendLine(logcat)
            }
        }
        out.writeText(body)
    }

    companion object {
        private val STAMP_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}

class CrashFileSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        CrashFileSender()

    override fun enabled(config: CoreConfiguration): Boolean = true
}
