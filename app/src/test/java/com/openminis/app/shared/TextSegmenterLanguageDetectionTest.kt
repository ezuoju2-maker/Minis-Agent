package com.openminis.app.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterLanguageDetectionTest {

    private fun isChinese(text: String) = TextSegmenter.isMajorityChinese(text)

    @Test
    fun `pure Chinese is majority Chinese`() {
        assertTrue(isChinese("苹果手机很好用"))
        assertTrue(isChinese("马云在杭州创办了阿里巴巴集团"))
        assertTrue(isChinese("中国科学院计算技术研究所"))
    }

    @Test
    fun `Chinese with embedded latin still routes Chinese when Han dominates`() {
        assertTrue(isChinese("苹果iPhone手机很好用"))
        assertTrue(isChinese("这个API接口返回JSON格式数据"))
    }

    @Test
    fun `pure english is not Chinese`() {
        assertFalse(isChinese("Hello world how are you"))
        assertFalse(isChinese("The quick brown fox jumps"))
    }

    @Test
    fun `kana presence routes kanji-heavy japanese away from Chinese`() {
        assertFalse(isChinese("東京は日本の首都です"))
    }

    @Test
    fun `kana-heavy japanese is not Chinese`() {
        assertFalse(isChinese("これはとてもおいしいラーメンでしたね"))
    }

    @Test
    fun `a single kana anywhere in the sample is enough`() {
        assertFalse(isChinese("東京都の人口"))
        assertFalse(isChinese("東京タワー"))
    }

    @Test
    fun `kana beyond the sample window does not affect routing`() {
        assertTrue(isChinese("中".repeat(200) + "これはひらがな"))
    }

    @Test
    fun `latin-heavy mixed text is not Chinese`() {
        assertFalse(isChinese("Meeting at 3pm in 会议室 with the whole team today"))
    }

    @Test
    fun `blank and empty are not Chinese`() {
        assertFalse(isChinese(""))
        assertFalse(isChinese("   "))
        assertFalse(isChinese("\n\t  "))
    }

    @Test
    fun `single latin char is not Chinese`() {
        assertFalse(isChinese("A"))
    }

    @Test
    fun `single Han char is Chinese`() {
        assertTrue(isChinese("好"))
    }

    @Test
    fun `whitespace is excluded from the ratio denominator`() {
        assertTrue(isChinese("   苹  果   手  机   "))
    }

    @Test
    fun `exactly half Han meets the threshold`() {
        assertTrue(isChinese("中文ab"))
    }

    @Test
    fun `only the first 200 chars are sampled`() {
        val text = "a".repeat(200) + "中文中文中文"
        assertFalse(isChinese(text))
        val text2 = "中".repeat(200) + "abcabc"
        assertTrue(isChinese(text2))
    }
}
