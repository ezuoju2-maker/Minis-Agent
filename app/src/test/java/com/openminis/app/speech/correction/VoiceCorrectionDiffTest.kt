package com.openminis.app.speech.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectionDiffTest {

    private val resegmentingTokenizer: (String) -> List<String> = { text ->
        when {
            text.startsWith("张山说") -> listOf("张山", "说") + rest(text, "张山说")
            text.startsWith("张三说") -> listOf("张三说") + rest(text, "张三说")
            text.contains("石塘") || text.contains("食堂") ->
                text.chunkedWords(listOf("石塘", "食堂", "张山", "张三"))
            else -> text.map { it.toString() }
        }
    }

    private fun rest(text: String, prefix: String): List<String> =
        text.removePrefix(prefix).map { it.toString() }

    private fun String.chunkedWords(words: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        outer@ while (i < length) {
            for (w in words) {
                if (startsWith(w, i)) { out.add(w); i += w.length; continue@outer }
            }
            out.add(this[i].toString()); i++
        }
        return out
    }

    private fun pairs(from: String, to: String) =
        VoiceCorrectionDiff.tokenPairs(from, to, resegmentingTokenizer)


    @Test
    fun `widens a minimal char replace out to the whole word`() {
        val p = pairs("张山说今天有空", "张三说今天有空")
        assertEquals(1, p.size)
        assertEquals("张山", p[0].from)
        assertEquals("张三", p[0].to)
    }

    @Test
    fun `handles multiple corrections in one sentence`() {
        val p = pairs("石塘在哪张山知道", "食堂在哪张三知道")
        assertTrue("expected 石塘→食堂 in $p", p.any { it.from == "石塘" && it.to == "食堂" })
        assertTrue("expected 张山→张三 in $p", p.any { it.from == "张山" && it.to == "张三" })
    }

    @Test
    fun `identical text yields no pairs`() {
        assertEquals(emptyList<VoiceCorrectionDiff.Pair>(), pairs("完全一样", "完全一样"))
    }

    @Test
    fun `empty input yields no pairs`() {
        assertEquals(emptyList<VoiceCorrectionDiff.Pair>(), pairs("", "食堂"))
        assertEquals(emptyList<VoiceCorrectionDiff.Pair>(), pairs("食堂", ""))
    }

    @Test
    fun `pure insertion produces no pair`() {
        val p = VoiceCorrectionDiff.tokenPairs("今天", "今天很好") { it.map { c -> c.toString() } }
        assertEquals(emptyList<VoiceCorrectionDiff.Pair>(), p)
    }

    @Test
    fun `pure deletion produces no pair`() {
        val p = VoiceCorrectionDiff.tokenPairs("今天很好", "今天") { it.map { c -> c.toString() } }
        assertEquals(emptyList<VoiceCorrectionDiff.Pair>(), p)
    }

    @Test
    fun `length-imbalanced replacements are dropped as rewrites`() {
        val p = VoiceCorrectionDiff.tokenPairs("我说甲", "我说乙丙丁戊己庚辛壬") {
            it.map { c -> c.toString() }
        }
        assertTrue("expected imbalanced replace to be dropped, got $p", p.isEmpty())
    }


    @Test
    fun `charChangeRatio is small for a targeted fix`() {
        val r = VoiceCorrectionDiff.charChangeRatio("张山说今天有空", "张三说今天有空")
        assertTrue("ratio=$r should be under the 0.5 cap", r < VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO)
    }

    @Test
    fun `charChangeRatio exceeds the cap for a full rewrite`() {
        val r = VoiceCorrectionDiff.charChangeRatio("张山说今天有空", "完全不同的另外一句话内容")
        assertTrue("ratio=$r should exceed the 0.5 cap", r > VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO)
    }

    @Test
    fun `charChangeRatio of identical text is zero`() {
        assertEquals(0.0, VoiceCorrectionDiff.charChangeRatio("一样", "一样"), 0.0001)
    }


    @Test
    fun `lcs detects a replace`() {
        val ops = LcsDiff.diff(listOf("张山", "说"), listOf("张三", "说"))
        assertTrue(
            "expected a Replace in $ops",
            ops.any { it is LcsDiff.Op.Replace && it.old == listOf("张山") && it.new == listOf("张三") },
        )
    }

    @Test
    fun `lcs of identical lists is a single equal run`() {
        val ops = LcsDiff.diff(listOf("a", "b"), listOf("a", "b"))
        assertEquals(1, ops.size)
        assertTrue(ops[0] is LcsDiff.Op.Equal)
    }
}
