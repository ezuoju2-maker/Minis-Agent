package com.openminis.app.ui.sandbox

import java.io.File
import java.net.URLDecoder

sealed class MarkdownPreviewLinkTarget {
    data object Ignore : MarkdownPreviewLinkTarget()

    data class Relative(val file: File) : MarkdownPreviewLinkTarget()

    data class Chat(val url: String) : MarkdownPreviewLinkTarget()
}

object MarkdownPreviewLinks {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun target(rawUrl: String, document: File): MarkdownPreviewLinkTarget {
        val url = rawUrl.trim()
        if (url.isEmpty() || url.startsWith("#")) return MarkdownPreviewLinkTarget.Ignore
        if (url.startsWith("/") || schemePrefix.containsMatchIn(url)) {
            return MarkdownPreviewLinkTarget.Chat(url)
        }
        val path = url.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return MarkdownPreviewLinkTarget.Ignore
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(path)
        val folder = document.absoluteFile.parentFile ?: return MarkdownPreviewLinkTarget.Ignore
        return MarkdownPreviewLinkTarget.Relative(File(folder, decoded).normalize())
    }
}
