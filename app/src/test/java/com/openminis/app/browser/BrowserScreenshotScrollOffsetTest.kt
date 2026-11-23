package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScreenshotScrollOffsetTest {

    private val src: String by lazy {
        val f = File("src/main/java/com/openminis/app/browser/BrowserUseManager.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    private val code: String by lazy {
        src.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private val captureBody: String by lazy {
        code.substringAfter("private suspend fun captureWebViewBitmap(").substringBefore("\n    private fun saveBitmapToFile")
    }

    @Test
    fun `the canvas is translated before the webview is drawn`() {
        val translateAt = captureBody.indexOf("canvas.translate(")
        val drawAt = captureBody.indexOf("webView.draw(canvas)")
        assertTrue("capture must compensate the scroll offset", translateAt >= 0)
        assertTrue("webView.draw(canvas) must still be the draw call", drawAt >= 0)
        assertTrue(
            "the translate must happen BEFORE the draw, or it compensates nothing",
            translateAt < drawAt,
        )
    }

    @Test
    fun `the offset is probed from JS, not taken from the View alone`() {
        assertTrue(
            "must read the page scroll offset from the renderer",
            code.contains("private suspend fun readCssScrollOffset()"),
        )
        assertTrue(
            "and the capture must use it",
            captureBody.contains("readCssScrollOffset()"),
        )
        assertTrue(
            "the CSS offset must be scaled to physical px by density",
            captureBody.contains("cssScroll.second * density"),
        )
    }

    @Test
    fun `the two scroll sources are selected between, never summed`() {
        assertTrue(
            "view scroll must win when populated",
            captureBody.contains("if (webView.scrollY != 0) webView.scrollY.toFloat() else"),
        )
        assertFalse(
            "the two sources must never be added together",
            captureBody.contains("* density + webView.scrollY") ||
                captureBody.contains("* density + webView.scrollX"),
        )
    }

    @Test
    fun `the scroll probe cannot stall a capture forever`() {
        assertTrue(code.contains("SCROLL_PROBE_TIMEOUT_MS"))
        assertTrue(
            "the probe must be wrapped in a timeout",
            code.substringAfter("private suspend fun readCssScrollOffset()")
                .substringBefore("\n    private")
                .contains("withTimeoutOrNull(SCROLL_PROBE_TIMEOUT_MS)"),
        )
    }

    @Test
    fun `full page capture waits for a repaint after stretching`() {
        val fullPage = code.substringAfter("if (fullPage) {").substringBefore("val bitmap = try {")
        val applyAt = fullPage.lastIndexOf("applyViewport(savedW, cssCappedHeight)")
        val delayAt = fullPage.indexOf("delay(FULL_PAGE_REPAINT_DELAY_MS)")
        assertTrue("full-page must stretch the viewport", applyAt >= 0)
        assertTrue("and then wait for the repaint", delayAt >= 0)
        assertTrue("the wait must come after the stretch", applyAt < delayAt)
        assertTrue(code.contains("FULL_PAGE_REPAINT_DELAY_MS"))
    }
}
