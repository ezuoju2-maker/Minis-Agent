package com.openminis.app.speech

object VoiceTextSanitizer {

    data class LinkPhrases(
        val linkToHost: String = "link to %s",
        val bareLink: String = "link",
    )

    private val DEFAULT_PHRASES = LinkPhrases()

    fun sanitize(text: String, phrases: LinkPhrases = DEFAULT_PHRASES): String {
        var s = stripMarkdown(text, phrases)
        s = rewriteBareUrls(s, phrases)
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            sb.append(if (isSpeakable(cp)) String(Character.toChars(cp)) else " ")
            i += Character.charCount(cp)
        }
        return sb.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    private fun stripMarkdown(text: String, phrases: LinkPhrases): String {
        var s = com.openminis.app.ui.markdown.InlineHtmlTags.stripForPreview(text)
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        s = s.replace(Regex("`([^`]+)`"), "$1")
        s = rewriteMarkdownLinks(s, phrases)
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        s = s.replace(Regex("__([^_]+)__"), "$1")
        s = s.replace(Regex("\\*([^*]+)\\*"), "$1")
        s = s.replace(Regex("(?<!\\w)_([^_]+)_(?!\\w)"), "$1")
        s = s.replace(Regex("~~([^~]+)~~"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        s = s.replace(Regex("(?m)^\\s{0,3}[-*+]\\s+"), "")
        s = s.replace(Regex("(?m)^\\s{0,3}\\d+[.)]\\s+"), "")
        s = s.replace(Regex("(?m)^\\s*\\|?[-:| ]+\\|?\\s*$"), " ")
        s = s.replace(Regex("\\|"), " ")
        s = s.replace(Regex("(?m)^\\s*([-*_])\\1{2,}\\s*$"), " ")
        s = s.replace(Regex("(?<!\\w)_+|_+(?!\\w)"), "")
        s = s.replace(Regex("[*~`]+"), "")
        return s
    }

    private fun rewriteMarkdownLinks(text: String, phrases: LinkPhrases): String {
        val re = Regex("!?\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)")
        return re.replace(text) { m ->
            val desc = m.groupValues[1].trim()
            if (desc.isEmpty()) linkPhrase(m.groupValues[2], phrases) else desc
        }
    }

    private fun rewriteBareUrls(text: String, phrases: LinkPhrases): String =
        Regex("https?://[^\\s)\\]]+").replace(text) { m -> linkPhrase(m.value, phrases) }

    private fun linkPhrase(url: String, phrases: LinkPhrases): String {
        val host = hostOf(url)
        return if (host.isEmpty()) phrases.bareLink else phrases.linkToHost.format(host)
    }

    internal fun hostOf(url: String): String {
        var s = url
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        s.indexOf('@').let { if (it >= 0) s = s.substring(it + 1) }
        s.indexOf(':').let { if (it >= 0) s = s.substring(0, it) }
        if (s.startsWith("www.")) s = s.substring(4)
        return s
    }

    private fun isSpeakable(cp: Int): Boolean {
        if (cp < 0x80) return true
        return when (cp) {
            in 0x200B..0x200F,
            in 0xFE00..0xFE0F,
            in 0x2190..0x21FF,
            in 0x2300..0x23FF,
            in 0x2460..0x24FF,
            in 0x2500..0x257F,
            in 0x2580..0x259F,
            in 0x25A0..0x25FF,
            in 0x2600..0x26FF,
            in 0x2700..0x27BF,
            in 0x2B00..0x2BFF,
            in 0x1F000..0x1FAFF,
            in 0xE0000..0xE007F,
            -> false
            0x20E3 -> false
            else -> true
        }
    }
}
