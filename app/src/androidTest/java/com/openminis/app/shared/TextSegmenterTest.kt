package com.openminis.app.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSegmenterTest {

    private lateinit var segmenter: TextSegmenter

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        segmenter = TextSegmenter.getInstance(context)
    }


    @Test
    fun chineseWithLatinKeepsLatinTokenIntact() {
        val words = segmenter.segment("苹果iPhone手机很好用")
        assertTrue("expected tokens, got $words", words.isNotEmpty())
        assertTrue("iPhone should stay whole, got $words", words.contains("iPhone"))
        assertEquals("苹果iPhone手机很好用", words.joinToString(""))
    }

    @Test
    fun chineseWithAcronymsRoutesToJieba() {
        val words = segmenter.segment("这个API接口返回JSON格式数据")
        assertTrue(words.isNotEmpty())
        assertTrue("got $words", words.contains("API"))
        assertTrue("got $words", words.contains("JSON"))
        assertEquals("这个API接口返回JSON格式数据", words.joinToString(""))
    }


    @Test
    fun resolvesHeShangAmbiguity() {
        val words = segmenter.segment("结婚的和尚未结婚的")
        assertEquals(listOf("结婚", "的", "和", "尚未", "结婚", "的"), words)
    }

    @Test
    fun resolvesPingPongAuctionAmbiguity() {
        val words = segmenter.segment("乒乓球拍卖完了")
        assertEquals(listOf("乒乓球", "拍卖", "完", "了"), words)
    }

    @Test
    fun resolvesNanjingBridgeAmbiguity() {
        val words = segmenter.segment("南京市长江大桥")
        assertEquals(listOf("南京市", "长江大桥"), words)
    }


    @Test
    fun keepsProperNounsTogether() {
        val words = segmenter.segment("马云在杭州创办了阿里巴巴集团")
        assertTrue("got $words", words.contains("马云"))
        assertTrue("got $words", words.contains("杭州"))
        assertTrue("got $words", words.contains("阿里巴巴"))
        assertEquals("马云在杭州创办了阿里巴巴集团", words.joinToString(""))
    }

    @Test
    fun segmentsInstitutionName() {
        val words = segmenter.segment("中国科学院计算技术研究所")
        assertTrue(words.isNotEmpty())
        assertEquals("中国科学院计算技术研究所", words.joinToString(""))
    }


    @Test
    fun englishRoutesToSystemEngine() {
        assertEquals(
            listOf("Hello", "world", "how", "are", "you"),
            segmenter.segment("Hello world how are you"),
        )
        assertEquals(
            listOf("The", "quick", "brown", "fox", "jumps"),
            segmenter.segment("The quick brown fox jumps"),
        )
    }

    @Test
    fun latinHeavyMixedTextRoutesToSystemEngine() {
        val words = segmenter.segment("他在Google工作了3年")
        assertTrue("got $words", words.contains("Google"))
        assertTrue(words.isNotEmpty())
    }

    @Test
    fun kanaHeavyJapaneseRoutesToSystemEngine() {
        val words = segmenter.segment("これはとてもおいしいラーメンでしたね")
        assertTrue(words.isNotEmpty())
    }

    @Test
    fun kanjiHeavyJapaneseRoutesToSystemEngine() {
        val words = segmenter.segment("東京は日本の首都です")
        assertTrue(words.isNotEmpty())
        assertTrue("got $words", words.any { it.contains("東京") })
    }


    @Test
    fun emptyInputReturnsEmptyList() {
        assertEquals(emptyList<String>(), segmenter.segment(""))
        assertEquals(emptyList<String>(), segmenter.segment("   "))
        assertEquals(emptyList<String>(), segmenter.segmentForSearch(""))
    }

    @Test
    fun singleLatinCharRoutesToSystemEngine() {
        assertEquals(listOf("A"), segmenter.segment("A"))
    }


    @Test
    fun searchModeProducesFinerChineseTokens() {
        val exact = segmenter.segment("中国科学院计算技术研究所")
        val search = segmenter.segmentForSearch("中国科学院计算技术研究所")
        assertTrue("exact=$exact search=$search", search.size >= exact.size)
        assertTrue(search.isNotEmpty())
    }


    @Test
    fun segmentsTenThousandCharsUnderOneSecond() {
        val text = "这是一个用于测试分词性能的中文句子。".repeat(600)
        assertTrue("sample must be ~10k chars, was ${text.length}", text.length >= 10_000)
        segmenter.segment("预热")
        val start = System.currentTimeMillis()
        val words = segmenter.segment(text)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(words.isNotEmpty())
        assertTrue("segment() took ${elapsed}ms for ${text.length} chars", elapsed < 1000)
    }
}
