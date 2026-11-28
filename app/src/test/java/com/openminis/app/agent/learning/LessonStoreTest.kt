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

class LessonRulesTest {

    @Test
    fun preferenceStatementsLandInThePreferenceBucket() {
        assertEquals(LessonStore.Category.PREFERENCE, LessonStore.inferCategory("我不吃辣，推荐餐厅别带辣"))
        assertEquals(LessonStore.Category.PREFERENCE, LessonStore.inferCategory("我来自湖北"))
    }

    @Test
    fun technicalAndWorkflowSignalsAreSeparated() {
        assertEquals(LessonStore.Category.TECHNICAL, LessonStore.inferCategory("这个 API 已经废弃，改用 v2 接口"))
        assertEquals(LessonStore.Category.WORKFLOW, LessonStore.inferCategory("以后先跑测试再提交"))
    }

    @Test
    fun promotionNeedsThreeHitsAndNoFlag() {
        assertTrue(!LessonStore.shouldPromote(2, false))
        assertTrue(LessonStore.shouldPromote(3, false))
        assertTrue(!LessonStore.shouldPromote(3, true))
    }

    @Test
    fun failuresAreNormalizedAndScrubbed() {
        val text = LessonStore.normalizeFailure(
            "shell_execute",
            "Error: gradle failed at /home/user/project/app/build.gradle line 1204, exit code 1",
        )
        assertNotNull(text)
        assertTrue(text!!.startsWith("shell_execute: "))
        assertTrue(text.contains("…"))
        assertTrue(!text.contains("1204"))
    }

    @Test
    fun ordinaryOutputIsNotAJournalledFailure() {
        assertEquals(null, LessonStore.normalizeFailure("shell_execute", "total 12\ndrwxr-xr-x 3 user"))
        assertEquals(null, LessonStore.normalizeFailure("shell_execute", "ok"))
    }

    @Test
    fun kindParsingAcceptsHumanWords() {
        assertEquals(LessonStore.Kind.BEST, LessonStore.Kind.parse("最佳实践"))
        assertEquals(LessonStore.Kind.GAP, LessonStore.Kind.parse("knowledge_gap"))
        assertEquals(LessonStore.Kind.ERROR, LessonStore.Kind.parse("错误"))
        assertEquals(LessonStore.Kind.CORRECTION, LessonStore.Kind.parse(null))
    }
}
