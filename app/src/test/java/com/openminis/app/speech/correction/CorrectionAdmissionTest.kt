package com.openminis.app.speech.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionAdmissionTest {

    private val zh = object : PhoneticNormalizer {
        private val table = mapOf(
            "审推" to "shentui", "闪退" to "shantui",
            "绘画" to "huihua", "会话" to "huihua",
            "上帝" to "shangdi", "上" to "shang",
            "十七" to "shiqi",
            "二V三" to "erv san",
            "挂载" to "guazai", "规则" to "guize",
            "最后是" to "zuihoushi", "是最后" to "shizuihou",
            "英雄" to "yingxiong",
            "看看下一个英雄" to "kankanxiayigeyingxiong",
            "看看下一个Issue" to "kankanxiayigeissue",
        )
        override val locale: String = "zh"

        override fun normalize(text: String): String =
            (table[text] ?: text.lowercase())
                .split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString("")
    }

    private fun judge(from: String, to: String, sentence: Int? = null) =
        CorrectionAdmission.judge(
            from, to, zh,
            sentence ?: maxOf(from.length, to.length, 40),
        )


    @Test
    fun `chinese homophones are admitted`() {
        assertTrue(judge("审推", "闪退").isAdmitted)
        assertTrue(judge("绘画", "会话").isAdmitted)
        assertTrue(judge("LifeActivity", "LiveActivity").isAdmitted)
    }

    @Test
    fun `chinese-english phonetic mixes are admitted`() {
        assertTrue(judge("cloud", "claude").isAdmitted)
        assertTrue(judge("worker", "work on").isAdmitted)
        assertTrue(judge("I worker", "I work on").isAdmitted)
    }

    @Test
    fun `acronym expansion is newly collected`() {
        assertEquals(CorrectionAdmission.Verdict.Acronym, judge("MediaS", "Media Server"))
        assertTrue(judge("上帝", "上").isAdmitted)
    }

    @Test
    fun `digit normalization is newly collected`() {
        assertEquals(CorrectionAdmission.Verdict.DigitNorm, judge("十七", "17"))
        assertEquals(CorrectionAdmission.Verdict.DigitNorm, judge("二V三", "v3"))
    }


    @Test
    fun `semantic rewrites are rejected`() {
        assertFalse(judge("挂载", "规则").isAdmitted)
        assertFalse(judge("secure", "SSH key").isAdmitted)
        assertFalse(judge("英雄", "Issue", sentence = 6).isAdmitted)
    }

    @Test
    fun `a pure reorder is rejected`() {
        assertEquals(CorrectionAdmission.Verdict.RejectedReorder, judge("最后是", "是最后"))
    }

    @Test
    fun `an edit spanning most of a short sentence is rejected`() {
        assertFalse(judge("看看下一个英雄", "看看下一个Issue", sentence = 8).isAdmitted)
    }

    @Test
    fun `locality alone flips the verdict on an identical span`() {
        assertTrue(judge("审推", "闪退", sentence = 20).isAdmitted)
        val v = judge("审推", "闪退", sentence = 2)
        assertTrue("expected RejectedTooGlobal, got ${v.reason}", v is CorrectionAdmission.Verdict.RejectedTooGlobal)
    }


    @Test
    fun `isPureReorder detects anagrams only`() {
        assertTrue(CorrectionAdmission.isPureReorder("最后是", "是最后"))
        assertTrue(CorrectionAdmission.isPureReorder("abc", "cba"))
        assertFalse(CorrectionAdmission.isPureReorder("审推", "闪退"))
        assertFalse(CorrectionAdmission.isPureReorder("abc", "abc"))
    }

    @Test
    fun `isAcronymRelated requires an ordered, meaningfully shorter subsequence`() {
        assertTrue(CorrectionAdmission.isAcronymRelated("medias", "mediaserver"))
        assertTrue(CorrectionAdmission.isAcronymRelated("shang", "shangdi"))
        assertFalse(CorrectionAdmission.isAcronymRelated("cat", "dog"))
        assertFalse(CorrectionAdmission.isAcronymRelated("abcd", "abce"))
        assertFalse(CorrectionAdmission.isAcronymRelated("acb", "abcd"))
        assertFalse(CorrectionAdmission.isAcronymRelated("abcdefghi", "abcdefghij"))
    }

    @Test
    fun `isDigitTermNormalization needs spoken-vs-digit on opposite sides`() {
        assertTrue(CorrectionAdmission.isDigitTermNormalization("十七", "17"))
        assertTrue(CorrectionAdmission.isDigitTermNormalization("二V三", "v3"))
        assertFalse(CorrectionAdmission.isDigitTermNormalization("挂载", "规则"))
        assertFalse(CorrectionAdmission.isDigitTermNormalization("17", "18"))
    }


    @Test
    fun `RTU to Retry is now collected by the latin-phonetic signal`() {
        val v = judge("RTU", "Retry")
        assertTrue("RTU->Retry should now be collected, got $v", v.isAdmitted)
        assertEquals(CorrectionAdmission.Verdict.LatinPhonetic, v)
    }


    @Test
    fun `the real-data matrix collects ASR errors and rejects rewrites`() {
        val shouldCollect = listOf(
            "审推" to "闪退", "绘画" to "会话", "LifeActivity" to "LiveActivity",
            "cloud" to "claude", "worker" to "work on", "I worker" to "I work on",
            "MediaS" to "Media Server", "十七" to "17", "二V三" to "v3",
        )
        val shouldReject = listOf(
            "挂载" to "规则", "secure" to "SSH key", "最后是" to "是最后",
        )
        for ((a, b) in shouldCollect) {
            val v = judge(a, b, sentence = 40)
            assertTrue("expected COLLECT for $a->$b, got ${v.reason}", v.isAdmitted)
        }
        for ((a, b) in shouldReject) {
            val v = judge(a, b, sentence = 40)
            assertFalse("expected REJECT for $a->$b, got ${v.reason}", v.isAdmitted)
        }
    }


    @Test
    fun `the old single-axis rule would have dropped these, the new one keeps them`() {
        fun oldRuleAdmits(from: String, to: String): Boolean {
            val sim = PinyinNormalizer.similarity(zh.normalize(from), zh.normalize(to))
            val lengthRatio = maxOf(from.length, to.length).toDouble() /
                maxOf(1, minOf(from.length, to.length)).toDouble()
            return sim >= 0.6 && lengthRatio <= 2.0
        }
        val recovered = listOf("MediaS" to "Media Server", "十七" to "17", "二V三" to "v3")
        for ((a, b) in recovered) {
            assertFalse("old rule should have DROPPED $a->$b", oldRuleAdmits(a, b))
            assertTrue("new rule must COLLECT $a->$b", judge(a, b, sentence = 40).isAdmitted)
        }
    }


    @Test
    fun `signal 4 admits linux to minis`() {
        val v = judge("linux", "minis", sentence = 40)
        assertTrue("linux->minis must be collected, got $v", v.isAdmitted)
        assertEquals(CorrectionAdmission.Verdict.LatinPhonetic, v)
    }

    @Test
    fun `signal 4 rejects unrelated words with no shared consonant slot`() {
        assertFalse(judge("cat", "dog", sentence = 40).isAdmitted)
        assertFalse(CorrectionAdmission.isLatinPhoneticSimilar("cat", "dog"))
    }

    @Test
    fun `signal 4 requires equal syllable counts`() {
        assertFalse(CorrectionAdmission.isLatinPhoneticSimilar("minis", "mn"))
    }

    @Test
    fun `signal 4 cannot fire on Chinese input`() {
        assertTrue(
            "raw signal admits the pinyin keys — the guard is what saves us",
            CorrectionAdmission.isLatinPhoneticSimilar("guazai", "guize"),
        )
        assertFalse(CorrectionAdmission.isLatinOrigin("挂载"))
        assertFalse("挂载->规则 must stay rejected", judge("挂载", "规则").isAdmitted)
    }

    @Test
    fun `isLatinOrigin accepts latin words with separators and digits`() {
        assertTrue(CorrectionAdmission.isLatinOrigin("linux"))
        assertTrue(CorrectionAdmission.isLatinOrigin("work on"))
        assertTrue(CorrectionAdmission.isLatinOrigin("gpl-v3"))
        assertTrue(CorrectionAdmission.isLatinOrigin("read_image.file"))
        assertFalse(CorrectionAdmission.isLatinOrigin("123"))
        assertFalse(CorrectionAdmission.isLatinOrigin(""))
        assertFalse(CorrectionAdmission.isLatinOrigin("linux挂载"))
        assertFalse(CorrectionAdmission.isLatinOrigin("café"))
    }

    @Test
    fun `signal 4 does not loosen the acknowledged near-threshold pair`() {
        assertFalse(CorrectionAdmission.isLatinPhoneticSimilar("cursor", "claude"))
    }

    @Test
    fun `signal 4 runs after the earlier signals`() {
        val v = judge("cloud", "claude", sentence = 40)
        assertTrue(v is CorrectionAdmission.Verdict.Homophone)
    }
}
