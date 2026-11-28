package com.openminis.app.preview

import java.io.File

object LocalHtmlTrust {

    private val TRUSTED_ROOTS = listOf(
        "/var/minis/workspace",
        "/var/minis/sessions",
        "/var/minis/skills",
    )

    fun isTrustedLocalUrl(url: String?): Boolean {
        if (url == null) return false
        if (!url.startsWith("file://")) return false
        val raw = url.removePrefix("file://").substringBefore('?').substringBefore('#')
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val path = runCatching { File(decoded).canonicalPath }.getOrDefault(decoded)
        return TRUSTED_ROOTS.any { root -> path == root || path.startsWith("$root/") }
    }

    fun fileAccessFlags(url: String?): Pair<Boolean, Boolean> {
        val trusted = isTrustedLocalUrl(url)
        return trusted to trusted
    }
}
