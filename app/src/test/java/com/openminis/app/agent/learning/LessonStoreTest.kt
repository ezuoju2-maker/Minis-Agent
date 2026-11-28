package com.openminis.app.agent.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonTextTest {

    @Test
    fun cjkBecomesBigramsAndLatinBecomesWords() {
        val t = LessonStore.tokens("报告放 /var/minis/out，不要放聊天正文")
        assertTrue(t.contains("报告"))
        assertTrue(t.contains("聊天"))
        assertTrue(t.contains("var"))
        assertTrue(t.contains("minis"))
    }

    @Test
    fun similarWordingScoresHigh() {
        val a = "把生成的报告放到 out 目录，不要直接贴到聊天里"
        val b = "生成的报告放 out 目录，别贴聊天正文"
        assertTrue(LessonStore.similarity(a, b) > 0.15)
    }

    @Test
    fun unrelatedTextScoresLow() {
        assertTrue(LessonStore.similarity("报告放 out 目录", "用中文回答，别用英文") < 0.2)
    }

    @Test
    fun relevanceCountsMatchedQueryTokens() {
        val hay = "电商主图的尺寸用 800x800，不要用 750"
        assertTrue(LessonStore.relevance("帮我做电商主图", hay) > 0.0)
        assertEquals(0.0, LessonStore.relevance("写一首诗", hay), 0.0)
    }

    @Test
    fun cleanCollapsesWhitespace() {
        assertEquals("a b c", LessonStore.clean("  a\n\t b   c "))
    }
}

class CorrectionDetectorTest {

    @Test
    fun chineseCorrectionsAreDetected() {
        assertTrue(CorrectionDetector.detect("不对，报告应该放在 out 目录"))
        assertTrue(CorrectionDetector.detect("以后都不要用英文回复"))
        assertTrue(CorrectionDetector.detect("你忘了上次说的格式"))
        assertTrue(CorrectionDetector.detect("应该是先跑测试再提交"))
    }

    @Test
    fun englishCorrectionsAreDetected() {
        assertTrue(CorrectionDetector.detect("That's not what I asked for"))
        assertTrue(CorrectionDetector.detect("from now on put files in out/"))
        assertTrue(CorrectionDetector.detect("don't do that again please"))
    }

    @Test
    fun ordinaryTaskRequestsAreNotCorrections() {
        assertTrue(!CorrectionDetector.detect("帮我做一份电商主图"))
        assertTrue(!CorrectionDetector.detect("今天天气怎么样"))
        assertTrue(!CorrectionDetector.detect("把这份 PDF 转成 Word"))
    }

    @Test
    fun nudgeAsksForOneImperativeRule() {
        val nudge = CorrectionDetector.nudge()
        assertNotNull(nudge)
        assertTrue(nudge.contains("lesson_write"))
        assertTrue(nudge.contains("ONE reusable rule"))
    }
}
