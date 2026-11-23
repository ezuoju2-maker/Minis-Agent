package com.openminis.app.data

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

object ContextOffload {
    const val LINUX_OFFLOADS_DIR = "/var/minis/offloads"

    const val OFFLOADED_PREFIX = "[CONTEXT OFFLOADED]"

    const val SYSTEM_NOTICE_TAG = "[Minis System Notice]"

    const val SYSTEM_REMINDER_OPEN = "<system-reminder>"

    fun toolsDir(context: Context, sessionId: String): File =
        File(context.filesDir, "minis-sessions/$sessionId/offloads/tools")

    private fun ensureToolsDir(context: Context, sessionId: String): File {
        val dir = toolsDir(context, sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun shortToolId(toolId: String): String =
        if (toolId.length <= 12) toolId else toolId.takeLast(12)

    private fun sanitize(name: String): String =
        name.ifEmpty { "tool" }.replace('/', '_')

    fun offloadContent(
        context: Context,
        sessionId: String,
        content: String,
        toolId: String,
        toolName: String,
        ext: String = "txt",
    ): String {
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "${sanitize(toolName)}_${shortToolId(toolId)}.$ext"
        val file = File(dir, fileName)
        return try {
            file.writeText(content)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadContent failed: ${e.message}")
            ""
        }
    }

    fun offloadImage(
        context: Context,
        sessionId: String,
        bytes: ByteArray,
        toolId: String,
        mimeType: String,
    ): String {
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "image_${shortToolId(toolId)}.$ext"
        val file = File(dir, fileName)
        return try {
            file.writeBytes(bytes)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadImage failed: ${e.message}")
            ""
        }
    }

    fun stub(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$OFFLOADED_PREFIX Content (~$approxTokens tokens, $byteCount bytes) saved to: $linuxPath\n" +
            "Use file_read tool to retrieve if needed."

    fun prunedArgumentNotice(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$SYSTEM_REMINDER_OPEN\n" +
            "$SYSTEM_NOTICE_TAG The original content (~$approxTokens tokens, " +
            "$byteCount bytes) of this tool argument has been pruned to save context " +
            "and offloaded to: $linuxPath\n\n" +
            "CRITICAL: This is a placeholder notice, NOT actual file content. NEVER pass " +
            "this placeholder or reuse it in subsequent file_write or file_edit calls. " +
            "If you need the original content, call file_read on the offloaded path first.\n" +
            "</system-reminder>"

    fun isOffloadPlaceholder(value: String): Boolean {
        val trimmed = value.trimStart()
        if (trimmed.startsWith(SYSTEM_REMINDER_OPEN) && trimmed.contains(SYSTEM_NOTICE_TAG)) {
            return true
        }
        return trimmed.startsWith(OFFLOADED_PREFIX)
    }

    fun placeholderWriteRefusal(field: String, path: String): String =
        "Error: '$field' is an offload placeholder, not real " +
            "content — the original text was moved out of context to free tokens and only " +
            "this reference remains in the conversation. Nothing was written to $path; the " +
            "file is unchanged. Use file_read on the path named inside the placeholder to " +
            "recover the real content, then re-issue this write with it."

    fun offloadReadbackPath(content: String): String? {
        if (!content.startsWith("[")) return null
        val close = content.indexOf(']')
        if (close <= 1) return null
        val header = content.substring(1, close)
        val path = header.substringBefore('|').trim()
        if (path.isEmpty()) return null
        if (path != LINUX_OFFLOADS_DIR && !path.startsWith("$LINUX_OFFLOADS_DIR/")) return null
        return path
    }

    private const val TAG = "ContextOffload"
}
