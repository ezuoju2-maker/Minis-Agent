package com.openminis.app.speech.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderAdmissionTest {

    private val fakeNormalizer = object : PhoneticNormalizer {
        private val table = mapOf(
            "张山" to "zhangshan", "张三" to "zhangsan",
            "石塘" to "shitang", "食堂" to "shitang",
            "吃饭" to "chifan", "看电影" to "kandianying",
            "张" to "zhang", "张张张张张" to "zhangzhangzhangzhangzhang",
        )
        override val locale: String = "zh"
        override fun normalize(text: String): String = table[text] ?: text.lowercase()
    }

    private fun admits(fromText: String, toText: String, sentence: Int = 40): Boolean =
        CorrectionAdmission.judge(fromText, toText, fakeNormalizer, sentence).isAdmitted

    @Test
    fun `a homophone fix is admitted`() {
        assertTrue(admits("张山", "张三"))
    }

    @Test
    fun `a near-homophone fix is admitted`() {
        assertTrue(admits("石塘", "食堂"))
    }

    @Test
    fun `an unrelated rewrite is rejected`() {
        assertFalse(admits("吃饭", "看电影"))
    }

    @Test
    fun `a much longer replacement is rejected`() {
        assertFalse(admits("张", "张张张张张", sentence = 6))
    }

    @Test
    fun `the same rule governs both capture sources`() {
        assertTrue(admits("石塘", "食堂"))
        assertFalse(admits("吃饭", "看电影"))
    }
}

class TypedVocabularyBuilderTest {

    @Test
    fun `attachment markup is removed`() {
        val text = "看看这个<user-attached-files>photo.png</user-attached-files>怎么样"
        assertTrue(
            TypedVocabularyBuilder.stripAttachmentMarkup(text) == "看看这个怎么样",
        )
    }

    @Test
    fun `text without markup is returned trimmed`() {
        assertTrue(TypedVocabularyBuilder.stripAttachmentMarkup("  普通消息  ") == "普通消息")
    }

    @Test
    fun `an unterminated markup opener is left alone`() {
        val text = "开头<user-attached-files>没有结束"
        assertTrue(TypedVocabularyBuilder.stripAttachmentMarkup(text) == text)
    }
}
