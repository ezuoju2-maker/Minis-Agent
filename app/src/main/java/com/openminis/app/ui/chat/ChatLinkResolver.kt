package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.sandbox.FileItem
import java.io.File

sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    data class SandboxFolder(val linuxPath: String) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()
}

object ChatLinkResolver {

    fun resolve(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ChatLinkAction.Web(rawUrl)

        val uri = runCatching { trimmed.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()

        if (scheme == "minis") {
            val action = DeepLinkHandler.parse(uri)
            if (action !is DeepLinkAction.Unknown) {
                return ChatLinkAction.DeepLink(action)
            }
        }

        val hostFile = resolveSandboxFile(trimmed, scheme, sessionId, context)
        android.util.Log.w("ChatLinkDiag",
            "resolve url=${trimmed.take(200)} sid=$sessionId hostFile=${hostFile?.absolutePath} exists=${hostFile?.exists()}")
        if (hostFile != null && hostFile.exists() && !hostFile.isDirectory) {
            FileItem.from(hostFile)?.let { return ChatLinkAction.SandboxFile(it) }
        }
        if (hostFile != null && hostFile.isDirectory) {
            sandboxFolderLinuxPath(trimmed, scheme, sessionId, context)
                ?.let { return ChatLinkAction.SandboxFolder(it) }
        }

        if (com.openminis.app.ui.browser.BrowserExternalSchemeHandler.shouldHandleExternally(trimmed)) {
            return ChatLinkAction.ExternalApp(trimmed)
        }

        return ChatLinkAction.Web(trimmed)
    }

    private fun sandboxFolderLinuxPath(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): String? {
        fun isDir(linuxPath: String): Boolean =
            (if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            })?.isDirectory == true
        val candidates = when (scheme) {
            "minis" -> minisPathCandidates(raw.removePrefix("minis://").substringBefore('?'))
                .map { if (it.startsWith("/")) it else "/var/minis/$it" }
            null -> listOf(raw).filter { it.startsWith("/") }
            else -> emptyList()
        }
        return candidates.firstOrNull { isDir(it) }?.trimEnd('/')?.ifEmpty { "/" }
    }

    private fun resolveSandboxFile(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): File? {
        fun lookup(linuxPath: String): File? =
            if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            }
        return when (scheme) {
            "minis" -> {
                val stripped = raw.removePrefix("minis://").substringBefore('?')
                minisPathCandidates(stripped)
                    .asSequence()
                    .map { candidate ->
                        val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
                        lookup(linuxPath)
                    }
                    .firstOrNull { it != null && it.exists() }
                    ?: lookup(
                        minisPathCandidates(stripped).first().let {
                            if (it.startsWith("/")) it else "/var/minis/$it"
                        },
                    )
            }
            "file" -> {
                val path = raw.removePrefix("file://").substringBefore('?')
                if (path.isEmpty()) null else File(java.net.URLDecoder.decode(path, "UTF-8"))
            }
            null -> {
                if (raw.startsWith("/")) lookup(raw) else null
            }
            else -> null
        }
    }

    internal fun minisPathCandidates(strippedPath: String): List<String> {
        fun decodeOnce(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                        continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        val once = decodeOnce(strippedPath)
        val candidates = mutableListOf(once)
        val twice = decodeOnce(once)
        if (twice != once) candidates.add(twice)
        return candidates
    }

    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
