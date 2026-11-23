package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


data class SoulMetadata(
    val name: String,
    val emoji: String,
    val icon: String,
    val style: String,
    val lang: String,
) {
    val displayEmoji: String get() = DISPLAY_EMOJI

    companion object {
        const val DISPLAY_EMOJI = "✨"

        val DEFAULT = SoulMetadata(
            name = "Minis",
            emoji = "",
            icon = "",
            style = "",
            lang = "auto",
        )
    }
}

data class SoulFile(val metadata: SoulMetadata, val body: String)

object SoulMDParser {

    fun parse(source: String): SoulFile {
        val trimmedLeading = source.dropWhile { it == '\n' || it == '\r' }
        if (!trimmedLeading.startsWith("---")) {
            return SoulFile(SoulMetadata.DEFAULT, source)
        }
        val lines = trimmedLeading.split("\n")
        if (lines.firstOrNull()?.trim() != "---") {
            return SoulFile(SoulMetadata.DEFAULT, source)
        }
        val closeIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closeIdx < 0) return SoulFile(SoulMetadata.DEFAULT, source)
        val absCloseIdx = closeIdx + 1
        val frontmatter = lines.subList(1, absCloseIdx)
        val bodyLines = if (absCloseIdx + 1 <= lines.size) lines.subList(absCloseIdx + 1, lines.size) else emptyList()
        val body = bodyLines.joinToString("\n").dropWhile { it == '\n' || it == '\r' }

        var name = SoulMetadata.DEFAULT.name
        var emoji = SoulMetadata.DEFAULT.emoji
        var icon = SoulMetadata.DEFAULT.icon
        var style = SoulMetadata.DEFAULT.style
        var lang = SoulMetadata.DEFAULT.lang
        for (raw in frontmatter) {
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            when (key) {
                "name" -> if (value.isNotEmpty()) name = value
                "emoji" -> if (value.isNotEmpty()) emoji = value
                "icon" -> icon = value
                "style" -> style = value
                "lang" -> if (value.isNotEmpty()) lang = value
                else -> Unit
            }
        }
        return SoulFile(SoulMetadata(name, emoji, icon, style, lang), body)
    }

    fun serialize(file: SoulFile): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: \"").append(escape(file.metadata.name)).append("\"\n")
        if (file.metadata.icon.isNotEmpty()) {
            sb.append("icon: \"").append(escape(file.metadata.icon)).append("\"\n")
        }
        sb.append("style: \"").append(escape(file.metadata.style)).append("\"\n")
        sb.append("lang: \"").append(escape(file.metadata.lang)).append("\"\n")
        sb.append("---\n\n")
        sb.append(file.body)
        if (!sb.endsWith("\n")) sb.append("\n")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}

sealed class SoulBodyLimitCheck {
    object Ok : SoulBodyLimitCheck()
    data class OverLimitChinese(val chars: Int, val cap: Int) : SoulBodyLimitCheck()
    data class OverLimitEnglish(val words: Int, val cap: Int) : SoulBodyLimitCheck()

    val isOverLimit: Boolean get() = this !is Ok
}

object SoulStore {

    private const val TAG = "SoulStore"
    private const val FILE_NAME = "SOUL.md"
    private const val MEMORY_SUBDIR = "minis-global/memory"

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    fun iconFileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), SoulIcon.SIDECAR_NAME)


    const val CJK_RATIO_THRESHOLD: Double = 0.3
    const val CHINESE_CHAR_LIMIT: Int = 1600
    const val ENGLISH_WORD_LIMIT: Int = 1000

    fun isOverLimit(body: String): SoulBodyLimitCheck {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return SoulBodyLimitCheck.Ok

        var cjk = 0
        var total = 0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            total += 1
            if (isCJKCodePoint(cp)) cjk += 1
            i += Character.charCount(cp)
        }
        val ratio = if (total > 0) cjk.toDouble() / total else 0.0
        return if (ratio > CJK_RATIO_THRESHOLD) {
            val chars = trimmed.codePointCount(0, trimmed.length)
            if (chars > CHINESE_CHAR_LIMIT) SoulBodyLimitCheck.OverLimitChinese(chars, CHINESE_CHAR_LIMIT)
            else SoulBodyLimitCheck.Ok
        } else {
            val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (words > ENGLISH_WORD_LIMIT) SoulBodyLimitCheck.OverLimitEnglish(words, ENGLISH_WORD_LIMIT)
            else SoulBodyLimitCheck.Ok
        }
    }

    private fun isCJKCodePoint(cp: Int): Boolean {
        if (cp in 0x4E00..0x9FFF) return true
        if (cp in 0x3400..0x4DBF) return true
        if (cp in 0x20000..0x2A6DF) return true
        if (cp in 0x2A700..0x2EBEF) return true
        if (cp in 0x30000..0x323AF) return true
        if (cp in 0x3040..0x309F) return true
        if (cp in 0x30A0..0x30FF) return true
        if (cp in 0x31F0..0x31FF) return true
        if (cp in 0xAC00..0xD7AF) return true
        if (cp in 0x1100..0x11FF) return true
        if (cp in 0x3130..0x318F) return true
        return false
    }

    val DEFAULT_CONTENT: String = """---
name: "Minis"
style: ""
lang: "auto"
---

**Don't perform — help.** Skip the "Sure!" and "Happy to assist!" — just do the work.

**Have a stance.** It's fine to disagree, prefer one thing over another, find some things interesting and others dull.

**Act first, ask second.** If you can look it up, look it up. Come back with answers, not questions.
"""

    fun ensureExists(context: Context) {
        val file = fileLocation(context)
        if (file.exists()) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_CONTENT)
            AppLogger.info(TAG, "seeded SOUL.md at ${file.absolutePath}")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ensureExists failed: ${t.message}")
        }
    }

    fun load(context: Context): SoulFile? {
        val file = fileLocation(context)
        if (!file.exists()) return null
        return try {
            val parsed = SoulMDParser.parse(file.readText())
            parsed.copy(metadata = parsed.metadata.copy(icon = resolveIconForRead(context, parsed.metadata.icon)))
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "SOUL.md load failed: ${t.message}")
            null
        }
    }

    private fun resolveIconForRead(context: Context, raw: String): String {
        if (!SoulIcon.isSidecarRef(raw)) return raw
        val png = runCatching { iconFileLocation(context).takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (png == null || png.isEmpty()) {
            AppLogger.warning(TAG, "SOUL icon sidecar missing or unreadable — treating as no icon")
            return ""
        }
        return SoulIcon.dataUri(png)
    }

    fun save(context: Context, file: SoulFile) {
        val target = fileLocation(context)
        target.parentFile?.mkdirs()
        val onDisk = file.copy(metadata = file.metadata.copy(icon = persistIconSidecar(context, file.metadata.icon)))
        val text = SoulMDParser.serialize(onDisk)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
        _cachedMetadata.value = file.metadata
    }

    private fun persistIconSidecar(context: Context, icon: String): String {
        val sidecar = iconFileLocation(context)
        val png = SoulIcon.pngBytes(icon)
        if (png == null) {
            if (!SoulIcon.isSidecarRef(icon)) runCatching { sidecar.delete() }
            return icon
        }
        return runCatching {
            sidecar.parentFile?.mkdirs()
            sidecar.writeBytes(png)
            SoulIcon.SIDECAR_NAME
        }.getOrElse {
            AppLogger.warning(TAG, "SOUL icon sidecar write failed (${it.message}) — storing inline")
            icon
        }
    }

    private val _cachedMetadata = MutableStateFlow(SoulMetadata.DEFAULT)
    val cachedMetadata: StateFlow<SoulMetadata> = _cachedMetadata.asStateFlow()

    fun refreshCache(context: Context) {
        val parsed = load(context)
        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
    }
}

object SystemPromptBuilder {

    private const val TAG = "Soul"

    val INJECTION_PATTERNS: List<Regex> = listOf(
        Regex("ignore.{0,30}previous.{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("disregard.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("forget.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
    )

    fun containsInjectionPattern(s: String): Boolean =
        INJECTION_PATTERNS.any { it.containsMatchIn(s) }

    private const val IDENTITY_TEMPLATE =
        "You are {name}, a capable AI assistant running on an Android device with a fully functional Linux sandbox (Ubuntu 24.04 via PRoot, aarch64). "

    fun identitySection(context: Context): String {
        val file = SoulStore.load(context)
        val name = (file?.metadata?.name ?: SoulMetadata.DEFAULT.name)
            .trim()
            .ifEmpty { "Minis" }

        val style = (file?.metadata?.style ?: "").trim()

        val identity = IDENTITY_TEMPLATE.replace("{name}", name)
        val identityTrimmed = identity.trimEnd()

        val soulEditHint =
            "---\n" +
            "SOUL.md fields (name / icon / style / lang / body) can be edited two ways:\n" +
            "1. Tool: call `minis-config` to propose changes (user must approve).\n" +
            "2. UI: ask the user to go to Settings → Soul to edit directly.\n" +
            "Pick whichever the user finds easier in context. Do not say you cannot change your personality."

        fun styleBlock(s: String): String {
            if (s.isEmpty()) return ""
            return "\n\nResponse style (from SOUL.md `style` — apply to every reply unless the user explicitly asks otherwise; if it prescribes a reply language, it overrides the default match-the-user's-language rule):\n$s"
        }

        val body = file?.body
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return identityTrimmed + styleBlock(style) + "\n\n" + soulEditHint + "\n\n"
        }

        val check = SoulStore.isOverLimit(trimmed)
        if (check.isOverLimit) {
            AppLogger.warning(TAG, "personality body is over the language-aware limit ($check) — falling back to identity-only system prompt.")
            return identityTrimmed + styleBlock(style) + "\n\n" + soulEditHint + "\n\n"
        }

        val personality = scrubInjections(trimmed)

        return identityTrimmed +
            "\n\nPersonality (from SOUL.md — your character and voice; defer to the user's latest message when it conflicts with anything here):\n" +
            personality +
            styleBlock(style) +
            "\n\n" +
            soulEditHint +
            "\n\n"
    }

    private fun scrubInjections(s: String): String =
        s.split("\n")
            .filter { line -> INJECTION_PATTERNS.none { it.containsMatchIn(line) } }
            .joinToString("\n")
}
