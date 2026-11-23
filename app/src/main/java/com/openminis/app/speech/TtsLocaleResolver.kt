package com.openminis.app.speech

import java.util.Locale

object TtsLocaleResolver {

    private val HAN = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")

    private val KANA = Regex("[\\u3040-\\u309f\\u30a0-\\u30ff]")

    private val HANGUL = Regex("[\\uac00-\\ud7af\\u1100-\\u11ff\\u3130-\\u318f]")
    private val CYRILLIC = Regex("[\\u0400-\\u04ff]")
    private val GREEK = Regex("[\\u0370-\\u03ff]")
    private val THAI = Regex("[\\u0e00-\\u0e7f]")
    private val HEBREW = Regex("[\\u0590-\\u05ff]")
    private val ARABIC = Regex("[\\u0600-\\u06ff]")
    private val DEVANAGARI = Regex("[\\u0900-\\u097f]")

    fun resolve(
        text: String,
        appLanguageTag: String?,
        systemLocale: Locale = Locale.getDefault(),
    ): Locale {
        parseTag(appLanguageTag)?.let { return it }

        scriptLocale(text)?.let { return it }

        if (systemLocale.language.isNotBlank()) return systemLocale
        return Locale.US
    }

    fun scriptLocale(text: String): Locale? {
        if (text.isBlank()) return null
        if (KANA.containsMatchIn(text)) return Locale.JAPANESE
        if (HANGUL.containsMatchIn(text)) return Locale.KOREAN
        if (HAN.containsMatchIn(text)) return Locale.SIMPLIFIED_CHINESE
        if (CYRILLIC.containsMatchIn(text)) return Locale("ru")
        if (GREEK.containsMatchIn(text)) return Locale("el")
        if (THAI.containsMatchIn(text)) return Locale("th")
        if (HEBREW.containsMatchIn(text)) return Locale("he")
        if (ARABIC.containsMatchIn(text)) return Locale("ar")
        if (DEVANAGARI.containsMatchIn(text)) return Locale("hi")
        return null
    }

    fun parseTag(tag: String?): Locale? {
        val code = tag?.trim().orEmpty()
        if (code.isEmpty()) return null
        return runCatching {
            if ('-' in code || '_' in code) {
                Locale.forLanguageTag(code.replace('_', '-'))
            } else {
                Locale(code)
            }
        }.getOrNull()?.takeIf { it.language.isNotBlank() }
    }

    fun candidates(locale: Locale): List<Locale> {
        val out = LinkedHashSet<Locale>()
        out.add(locale)
        if (locale.country.isNotBlank() || locale.variant.isNotBlank()) {
            out.add(Locale(locale.language))
        }
        return out.toList()
    }
}
