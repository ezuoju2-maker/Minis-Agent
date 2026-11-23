package com.openminis.app.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionReplacementTest {

    private fun detect(previous: TextFieldValue, next: TextFieldValue): Pair<String, String>? {
        val selection = previous.selection
        if (selection.collapsed) return null
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)
        val oldText = previous.text
        if (start < 0 || end > oldText.length || start >= end) return null
        val replaced = oldText.substring(start, end)
        if (replaced.isBlank()) return null
        val prefix = oldText.substring(0, start)
        val suffix = oldText.substring(end)
        val newText = next.text
        if (!newText.startsWith(prefix) || !newText.endsWith(suffix)) return null
        val insertedEnd = newText.length - suffix.length
        if (insertedEnd < prefix.length) return null
        val inserted = newText.substring(prefix.length, insertedEnd)
        if (inserted.isBlank() || inserted == replaced) return null
        return replaced to inserted
    }

    private fun value(text: String, start: Int, end: Int) =
        TextFieldValue(text, TextRange(start, end))

    @Test
    fun `replacing a selected span is detected`() {
        val before = value("我们约在石塘见面", 4, 6)
        val after = TextFieldValue("我们约在食堂见面")
        assertEquals("石塘" to "食堂", detect(before, after))
    }

    @Test
    fun `replacing a span at the start is detected`() {
        val before = value("石塘在哪", 0, 2)
        val after = TextFieldValue("食堂在哪")
        assertEquals("石塘" to "食堂", detect(before, after))
    }

    @Test
    fun `replacing a span at the end is detected`() {
        val before = value("我们去石塘", 3, 5)
        val after = TextFieldValue("我们去食堂")
        assertEquals("石塘" to "食堂", detect(before, after))
    }

    @Test
    fun `a reversed selection range still works`() {
        val before = value("我们约在石塘见面", 6, 4)
        val after = TextFieldValue("我们约在食堂见面")
        assertEquals("石塘" to "食堂", detect(before, after))
    }


    @Test
    fun `ordinary typing is ignored`() {
        val before = value("我们约在", 4, 4)
        val after = TextFieldValue("我们约在食")
        assertNull(detect(before, after))
    }

    @Test
    fun `deleting a selection is ignored`() {
        val before = value("我们约在石塘见面", 4, 6)
        val after = TextFieldValue("我们约在见面")
        assertNull(detect(before, after))
    }

    @Test
    fun `replacing with identical text is ignored`() {
        val before = value("我们约在石塘见面", 4, 6)
        val after = TextFieldValue("我们约在石塘见面")
        assertNull(detect(before, after))
    }

    @Test
    fun `a whitespace-only selection is ignored`() {
        val before = value("甲   乙", 1, 4)
        val after = TextFieldValue("甲乙丙")
        assertNull(detect(before, after))
    }

    @Test
    fun `an edit that also changes the surrounding text is ignored`() {
        val before = value("我们约在石塘见面", 4, 6)
        val after = TextFieldValue("他们约在食堂见面")
        assertNull(detect(before, after))
    }

    @Test
    fun `clearing the whole field via select-all is ignored`() {
        val before = value("石塘", 0, 2)
        val after = TextFieldValue("")
        assertNull(detect(before, after))
    }

    @Test
    fun `an out-of-range selection is clamped by TextFieldValue and still handled`() {
        val before = TextFieldValue("短", TextRange(0, 5))
        val after = TextFieldValue("长")
        assertEquals("短" to "长", detect(before, after))
    }
}
