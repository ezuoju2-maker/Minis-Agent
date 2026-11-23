package com.openminis.app.speech

import java.util.Locale

object RecognizerLocales {
    val BUILTIN_TAGS: List<String> = listOf(
        "en-US", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE", "it-IT",
        "pt-BR", "ru-RU", "ar-SA", "hi-IN", "id-ID", "th-TH", "vi-VN", "tr-TR",
    )

    fun recognizerTag(locale: Locale): String {
        val lang = locale.language
        var region = locale.country
        if (region.isNullOrEmpty() && lang == "zh") {
            region = if (locale.script == "Hant") "TW" else "CN"
        }
        if (region.isNullOrEmpty()) {
            region = runCatching { Locale.Builder().setLocale(locale).build() }
                .getOrNull()?.country.orEmpty()
        }
        return if (region.isEmpty()) lang else "$lang-$region"
    }

    fun recognizerTag(tag: String): String =
        recognizerTag(Locale.forLanguageTag(tag.trim().replace('_', '-')))
}
