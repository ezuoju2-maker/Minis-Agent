package com.openminis.app.ui.chat

import java.util.Locale

internal const val TITLE_GEN_SYSTEM_PROMPT: String =
    "You generate concise titles for conversations. You MUST respond with a single valid JSON object: {\"title\": \"...\", \"category\": \"...\"}. No other text."

internal fun titleLanguageDirective(locale: Locale = Locale.getDefault()): String {
    val (code, human) = resolveTitleLanguageCode(locale)
    return buildString {
        append("\n\n")
        append("The user's app interface language is \"").append(code)
            .append("\" (").append(human).append("). Generate the title primarily in this language. ")
        append("If the conversation content is in a different language, you may keep proper nouns ")
        append("from it, but the overall title language should match the interface language.\n")
        append("用户的 App 界面语言是 \"").append(code).append("\"（").append(human).append("）。")
        append("请优先使用该语言生成标题。如果对话内容是其他语言，可保留专有名词，但标题整体语言应与界面语言一致。")
    }
}

private fun resolveTitleLanguageCode(locale: Locale): Pair<String, String> {
    return try {
        val lang = locale.language.takeIf { it.isNotEmpty() } ?: return "en" to "English"
        if (lang == "zh") {
            val script = locale.script
            val region = locale.country
            val isTraditional = script.equals("Hant", ignoreCase = true)
                || region in setOf("TW", "HK", "MO")
            return if (isTraditional) "zh-Hant" to "繁體中文 / Traditional Chinese"
            else "zh-Hans" to "简体中文 / Simplified Chinese"
        }
        val human = humanReadable[lang] ?: locale.getDisplayLanguage(Locale.ENGLISH)
            .ifEmpty { lang }
        lang to human
    } catch (_: Exception) {
        "en" to "English"
    }
}

private val humanReadable: Map<String, String> = mapOf(
    "en" to "English",
    "ja" to "日本語 / Japanese",
    "ko" to "한국어 / Korean",
    "fr" to "Français / French",
    "de" to "Deutsch / German",
    "es" to "Español / Spanish",
    "it" to "Italiano / Italian",
    "pt" to "Português / Portuguese",
    "ru" to "Русский / Russian",
    "ar" to "العربية / Arabic",
    "hi" to "हिन्दी / Hindi",
    "vi" to "Tiếng Việt / Vietnamese",
    "th" to "ไทย / Thai",
    "id" to "Bahasa Indonesia / Indonesian",
    "tr" to "Türkçe / Turkish",
    "nl" to "Nederlands / Dutch",
    "pl" to "Polski / Polish",
    "hr" to "Hrvatski / Croatian",
    "hu" to "Magyar / Hungarian",
    "cs" to "Čeština / Czech",
    "fa" to "فارسی / Persian",
)
