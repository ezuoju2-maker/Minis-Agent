package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexCodeMaskTest {

    private fun latexOf(md: String): List<String> =
        MarkdownParser.parseWithMath(md).mathSpans.map { it.latex }

    private fun renderedText(md: String): String =
        MarkdownParser.parseWithMath(md).blocks.joinToString("\n") { it.toString() }

    @Test
    fun `unclosed dollar-dollar does not pair with one inside a later code fence`() {
        val md = """
            Here is an unclosed display delimiter: ${'$'}${'$'}

            This paragraph must survive.

            And so must this one.

            ```python
            print("${'$'}${'$'} inside a fence is not a closer")
            ```

            Tail paragraph.
        """.trimIndent()

        assertEquals(emptyList<String>(), latexOf(md))

        val text = renderedText(md)
        assertTrue("middle paragraph was swallowed", text.contains("This paragraph must survive"))
        assertTrue("second paragraph was swallowed", text.contains("And so must this one"))
        assertTrue("tail paragraph was swallowed", text.contains("Tail paragraph"))
    }

    @Test
    fun `ordinary single-line display math still renders`() {
        val md = "Mass energy: ${'$'}${'$'}E = mc^2${'$'}${'$'} done."
        assertEquals(listOf("E = mc^2"), latexOf(md))
    }

    @Test
    fun `ordinary multi-line display math still renders`() {
        val md = """
            ${'$'}${'$'}
            \sum_{i=1}^{n} i = \frac{n(n+1)}{2}
            ${'$'}${'$'}
        """.trimIndent()
        assertEquals(1, latexOf(md).size)
        assertTrue(latexOf(md).first().contains("\\sum"))
    }

    @Test
    fun `glyph-free multi-line display math still renders`() {
        val md = """
            ${'$'}${'$'}
            1 + 2 = 3
            ${'$'}${'$'}
        """.trimIndent()
        val spans = latexOf(md)
        assertEquals(1, spans.size)
        assertEquals("1 + 2 = 3", spans.first().trim())
    }

    @Test
    fun `multi-line body containing a blank line is rejected as prose`() {
        val md = """
            Opening ${'$'}${'$'} here

            some prose in between

            and a stray ${'$'}${'$'} much later
        """.trimIndent()
        assertEquals(emptyList<String>(), latexOf(md))
        assertTrue(renderedText(md).contains("some prose in between"))
    }

    @Test
    fun `inline math closer inside an inline code span is not used`() {
        val md = "value ${'$'}x and then `a ${'$'} b` tail"
        assertEquals(emptyList<String>(), latexOf(md))
        assertTrue(renderedText(md).contains("tail"))
    }

    @Test
    fun `normal inline math is unaffected`() {
        assertEquals(listOf("x^2"), latexOf("The square ${'$'}x^2${'$'} is here."))
    }
}
