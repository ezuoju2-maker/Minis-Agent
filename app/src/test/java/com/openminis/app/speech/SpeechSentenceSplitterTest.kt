package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSentenceSplitterTest {

    private fun extract(text: String, streaming: Boolean = true): Pair<List<String>, String> {
        val buf = StringBuilder(text)
        val out = SpeechSentenceSplitter.extractCompleteSentences(buf, streaming)
        return out to buf.toString()
    }


    @Test
    fun `a decimal point is not a sentence boundary`() {
        val (sentences, _) = extract("圆周率是 3.14 左右。", streaming = false)
        assertEquals(listOf("圆周率是 3.14 左右。"), sentences)
    }

    @Test
    fun `thousands separators and decimals in one number do not split`() {
        val (sentences, _) = extract("总计 1,000.50 元。", streaming = false)
        assertEquals(listOf("总计 1,000.50 元。"), sentences)
    }

    @Test
    fun `a european decimal comma does not split`() {
        val (sentences, _) = extract("Das kostet 28,98 Euro.", streaming = false)
        assertEquals(listOf("Das kostet 28,98 Euro."), sentences)
    }

    @Test
    fun `a dotted version string does not split`() {
        val (sentences, _) = extract("版本 1.11.2 已发布。", streaming = false)
        assertEquals(listOf("版本 1.11.2 已发布。"), sentences)
    }


    @Test
    fun `a normal sentence period still splits`() {
        val (sentences, _) = extract("This is one. This is two.", streaming = false)
        assertEquals(listOf("This is one.", "This is two."), sentences)
    }

    @Test
    fun `a period right after a number still splits when a non-digit follows`() {
        val (sentences, _) = extract("He scored 5. Then he left.", streaming = false)
        assertEquals(listOf("He scored 5.", "Then he left."), sentences)
    }

    @Test
    fun `cjk and ascii terminators and newlines all still split`() {
        val (sentences, _) = extract("第一句。第二句！第三句？fourth!\n", streaming = false)
        assertEquals(listOf("第一句。", "第二句！", "第三句？", "fourth!"), sentences)
    }

    @Test
    fun `a long numeric text still splits at its real terminators`() {
        val (sentences, _) = extract("A 是 1.5。B 是 2.25。C 是 3.125。", streaming = false)
        assertEquals(listOf("A 是 1.5。", "B 是 2.25。", "C 是 3.125。"), sentences)
    }


    @Test
    fun `a trailing digit-period is held back while streaming`() {
        val (sentences, remaining) = extract("price 28.", streaming = true)
        assertTrue("nothing spoken yet, got: $sentences", sentences.isEmpty())
        assertEquals("price 28.", remaining)
    }

    @Test
    fun `the held-back fragment resolves once the next delta arrives`() {
        val buf = StringBuilder("price 28.")
        assertTrue(SpeechSentenceSplitter.extractCompleteSentences(buf).isEmpty())
        buf.append("98 today.")
        val out = SpeechSentenceSplitter.extractCompleteSentences(buf)
        assertEquals(listOf("price 28.98 today."), out)
    }

    @Test
    fun `a trailing period after a non-digit is not held back`() {
        val (sentences, _) = extract("Hello there.", streaming = true)
        assertEquals(listOf("Hello there."), sentences)
    }

    @Test
    fun `the final flush is not held back`() {
        val (sentences, _) = extract("price 28.", streaming = false)
        assertEquals(listOf("price 28."), sentences)
    }


    @Test
    fun `consumed prefix is removed and the tail fragment is kept`() {
        val (sentences, remaining) = extract("Done. Still typing", streaming = false)
        assertEquals(listOf("Done."), sentences)
        assertEquals(" Still typing", remaining)
    }


    @Test
    fun `isIntraNumberSeparator requires digits on both sides`() {
        assertTrue(SpeechSentenceSplitter.isIntraNumberSeparator("3.14", 1))
        assertTrue(SpeechSentenceSplitter.isIntraNumberSeparator("1,000", 1))
        assertTrue(!SpeechSentenceSplitter.isIntraNumberSeparator("abc.14", 3))
        assertTrue(!SpeechSentenceSplitter.isIntraNumberSeparator("3.abc", 1))
        assertTrue(!SpeechSentenceSplitter.isIntraNumberSeparator("3!4", 1))
        assertTrue(!SpeechSentenceSplitter.isIntraNumberSeparator(".14", 0))
        assertTrue(!SpeechSentenceSplitter.isIntraNumberSeparator("14.", 2))
    }
}
