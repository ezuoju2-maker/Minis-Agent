package com.openminis.app.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsLocaleResolverTest {


    @Test
    fun `spanish text with the app in spanish is spoken in spanish`() {
        val l = TtsLocaleResolver.resolve("Hola, ¿cómo estás hoy?", appLanguageTag = "es")
        assertEquals("es", l.language)
    }

    @Test
    fun `other latin-script app languages are honoured`() {
        val cases = mapOf(
            "de" to "Guten Morgen, wie geht es dir?",
            "fr" to "Bonjour, comment allez-vous ?",
            "hr" to "Dobro jutro, kako ste?",
            "pl" to "Dzień dobry, jak się masz?",
            "tr" to "Günaydın, nasılsın?",
            "ro" to "Bună dimineața, ce mai faci?",
            "ms" to "Selamat pagi, apa khabar?",
            "fil" to "Magandang umaga, kumusta ka?",
        )
        for ((tag, text) in cases) {
            assertEquals(
                "text in $tag must not be spoken as English",
                tag,
                TtsLocaleResolver.resolve(text, appLanguageTag = tag).language,
            )
        }
    }

    @Test
    fun `region qualified app language keeps its region`() {
        val l = TtsLocaleResolver.resolve("Bom dia, tudo bem?", appLanguageTag = "pt-BR")
        assertEquals("pt", l.language)
        assertEquals("BR", l.country)
    }


    @Test
    fun `the app language wins over the text's script`() {
        val l = TtsLocaleResolver.resolve("你好，今天过得怎么样？", appLanguageTag = "es")
        assertEquals("es", l.language)
    }

    @Test
    fun `script still decides when no app language is set`() {
        val l = TtsLocaleResolver.resolve(
            "你好，今天过得怎么样？",
            appLanguageTag = null,
            systemLocale = Locale(""),
        )
        assertEquals("zh", l.language)
    }

    @Test
    fun `japanese with kanji is japanese, not chinese`() {
        assertEquals("ja", TtsLocaleResolver.resolve("今日はいい天気ですね", null).language)
        assertEquals("ja", TtsLocaleResolver.resolve("カタカナのテスト", null).language)
    }

    @Test
    fun `korean, russian, greek, thai, hebrew, arabic and hindi are detected`() {
        val cases = mapOf(
            "ko" to "안녕하세요, 오늘 어떠세요?",
            "ru" to "Привет, как дела?",
            "el" to "Γεια σου, τι κάνεις;",
            "th" to "สวัสดีครับ สบายดีไหม",
            "he" to "שלום, מה שלומך?",
            "ar" to "مرحبا، كيف حالك؟",
            "hi" to "नमस्ते, आप कैसे हैं?",
        )
        for ((lang, text) in cases) {
            assertEquals(text, lang, TtsLocaleResolver.resolve(text, null).language)
        }
    }

    @Test
    fun `a german ui speaks german even for non-latin text`() {
        assertEquals("de", TtsLocaleResolver.resolve("这是中文", appLanguageTag = "de").language)
    }

    @Test
    fun `the documented priority chain holds end to end`() {
        assertEquals(
            "hr",
            TtsLocaleResolver.resolve("你好", appLanguageTag = "hr", systemLocale = Locale.JAPAN).language,
        )
        assertEquals(
            "ko",
            TtsLocaleResolver.resolve("안녕하세요", appLanguageTag = "", systemLocale = Locale("")).language,
        )
        assertEquals(
            "fr",
            TtsLocaleResolver.resolve("Bonjour", appLanguageTag = "", systemLocale = Locale.FRANCE).language,
        )
        assertEquals(
            Locale.US,
            TtsLocaleResolver.resolve("Hello", appLanguageTag = null, systemLocale = Locale("")),
        )
    }

    @Test
    fun `latin script yields no script locale`() {
        assertNull(TtsLocaleResolver.scriptLocale("Hola, buenos días"))
        assertNull(TtsLocaleResolver.scriptLocale("12345 !!! 🎉"))
        assertNull(TtsLocaleResolver.scriptLocale("   "))
    }


    @Test
    fun `following the system uses the system locale`() {
        val l = TtsLocaleResolver.resolve("Guten Tag", appLanguageTag = "", systemLocale = Locale.GERMANY)
        assertEquals("de", l.language)
    }

    @Test
    fun `a null app language falls through to the system`() {
        val l = TtsLocaleResolver.resolve("Bonjour", appLanguageTag = null, systemLocale = Locale.FRANCE)
        assertEquals("fr", l.language)
    }

    @Test
    fun `english is still reachable as the final fallback`() {
        assertEquals(
            "en",
            TtsLocaleResolver.resolve("Hello there", appLanguageTag = "en").language,
        )
        assertEquals(
            Locale.US,
            TtsLocaleResolver.resolve("Hello", appLanguageTag = null, systemLocale = Locale(""))
        )
    }


    @Test
    fun `tags are parsed the way the picker stores them`() {
        assertEquals("es", TtsLocaleResolver.parseTag("es")?.language)
        assertEquals("zh", TtsLocaleResolver.parseTag("zh-Hant")?.language)
        assertEquals("Hant", TtsLocaleResolver.parseTag("zh-Hant")?.script)
        assertEquals("BR", TtsLocaleResolver.parseTag("pt-BR")?.country)
        assertNull(TtsLocaleResolver.parseTag(""))
        assertNull(TtsLocaleResolver.parseTag("   "))
        assertNull(TtsLocaleResolver.parseTag(null))
    }

    @Test
    fun `indonesian parses to a usable language on either platform`() {
        val l = TtsLocaleResolver.parseTag("id")
        assertEquals(true, l != null && l.language.isNotBlank())
        assertEquals(true, l!!.language == "id" || l.language == "in")
    }


    @Test
    fun `candidates degrade from region-qualified to bare language`() {
        assertEquals(
            listOf(Locale("pt", "BR"), Locale("pt")),
            TtsLocaleResolver.candidates(Locale("pt", "BR")),
        )
    }

    @Test
    fun `a bare language yields a single candidate`() {
        assertEquals(listOf(Locale("es")), TtsLocaleResolver.candidates(Locale("es")))
    }
}
