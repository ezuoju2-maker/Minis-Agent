package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamingFadeRelayoutTest {

    private fun src(rel: String): String {
        val f = File("src/main/java/com/openminis/app/ui/chat/$rel")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val md by lazy { src("StreamingMarkdownText.kt") }
    private val fade by lazy { src("StreamingFade.kt") }

    @Test
    fun `the per-frame AnnotatedString overlay is gone`() {
        assertFalse("FadeController.overlay() rebuilt the whole string every frame", fade.contains("fun overlay("))
        assertFalse("MdText must not derive its Text from a per-frame overlay", md.contains("effectiveText"))
    }

    @Test
    fun `fade alphas are consumed from a draw lambda, not composition`() {
        val idx = md.indexOf("fadeController.forEachActive")
        assertTrue("MdText must consume the fade via forEachActive", idx >= 0)
        val before = md.substring((idx - 800).coerceAtLeast(0), idx)
        assertTrue("forEachActive must be called inside drawWithContent", before.lastIndexOf(".drawWithContent {") > before.lastIndexOf("Text("))
        assertEquals("exactly one consumer of forEachActive in MdText", 1, Regex("fadeController\\.forEachActive").findAll(md).count())
    }

    @Test
    fun `the mask lives in its own layer above the text`() {
        val start = md.indexOf("val fadeMaskModifier")
        assertTrue(start >= 0)
        val block = md.substring(start, md.indexOf("} else Modifier", start))
        val outer = block.indexOf(".graphicsLayer()")
        val draw = block.indexOf(".drawWithContent {")
        val inner = block.indexOf(".graphicsLayer()", outer + 1)
        assertTrue("outer graphicsLayer must precede drawWithContent", outer in 0 until draw)
        assertTrue("an inner graphicsLayer must follow drawWithContent", inner > draw)
        assertTrue("mask draws ABOVE the text: drawContent() first", block.indexOf("drawContent()") in (draw + 1) until block.indexOf("forEachActive"))
    }

    @Test
    fun `the mask modifier is applied to the Text and only when fading`() {
        assertTrue(md.contains(".then(fadeMaskModifier)"))
        assertTrue("non-fading blocks must pay nothing", md.contains("val fadeMaskModifier = if (fadeController != null) {"))
    }
}
