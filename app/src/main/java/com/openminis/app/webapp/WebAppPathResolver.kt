package com.openminis.app.webapp

import android.content.Context
import com.openminis.app.data.db.WebAppShortcutEntity
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.sandbox.PRootKernel
import java.io.File

object WebAppPathResolver {

    fun resolve(context: Context, shortcut: WebAppShortcutEntity): File? {
        val file = when (shortcut.pathScope) {
            WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT -> resolveSession(context, shortcut)
            WebAppShortcutRepository.SCOPE_SHARED -> PRootKernel.resolveHostPath(shortcut.htmlPath)
            WebAppShortcutRepository.SCOPE_MOUNT -> PRootKernel.resolveHostPath(shortcut.htmlPath)
            else -> null
        }
        return file?.takeIf { it.exists() && it.isFile }
    }

    fun inferScope(hostFile: File): Triple<String, String?, String>? {
        val hostAbs = hostFile.absolutePath
        val sorted = com.openminis.app.sandbox.PRootKernel
            .bindMounts.entries.sortedByDescending { it.value.length }
        for ((linuxPrefix, hostBase) in sorted) {
            val baseNorm = hostBase.trimEnd('/')
            if (hostAbs == baseNorm || hostAbs.startsWith("$baseNorm/")) {
                val tail = hostAbs.removePrefix(baseNorm).removePrefix("/")
                val linuxPath = if (tail.isEmpty()) linuxPrefix else "$linuxPrefix/$tail"
                return when {
                    linuxPrefix == "/var/minis/shared" ->
                        Triple(WebAppShortcutRepository.SCOPE_SHARED, null, linuxPath)
                    linuxPrefix.startsWith("/var/minis/mounts/") -> {
                        val mountName = linuxPrefix.removePrefix("/var/minis/mounts/")
                            .substringBefore('/')
                        Triple(WebAppShortcutRepository.SCOPE_MOUNT, mountName, linuxPath)
                    }
                    else -> null
                }
            }
        }
        return null
    }

    private fun resolveSession(context: Context, shortcut: WebAppShortcutEntity): File? {
        val sessionId = shortcut.scopeContext ?: return null
        if (shortcut.htmlPath.startsWith("/var/minis/")) {
            return PRootKernel.resolveSessionHostPath(sessionId, shortcut.htmlPath, context)
        }
        val attachmentsDir = File(context.filesDir, "sessions/$sessionId/attachments")
        return File(attachmentsDir, shortcut.htmlPath)
    }
}
