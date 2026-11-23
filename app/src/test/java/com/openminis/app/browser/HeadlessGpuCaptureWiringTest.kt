package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessGpuCaptureWiringTest {

    private val manager = File("src/main/java/com/openminis/app/browser/BrowserUseManager.kt").readText()
    private val host = File("src/main/java/com/openminis/app/browser/HeadlessRenderHost.kt").readText()

    @Test
    fun `agent screenshots use the GPU host, the live preview and full-page stay on the software draw`() {
        assertTrue("viewport screenshot", manager.contains("captureWebViewBitmap(gpu = !didStretch)"))
        assertTrue("auto snapshot after an action", manager.contains("captureWebViewBitmap(gpu = true) ?: return result"))
        assertTrue(manager.contains("suspend fun captureLiveSnapshot(): Bitmap? = captureWebViewBitmap()"))
        assertTrue(
            "a GPU miss falls through to the software draw",
            manager.contains("if (gpu) HeadlessRenderHost.capture(webView, w, h)?.let { return@withContext it }"),
        )
    }

    @Test
    fun `a WebView on screen in the browser sheet is never taken`() {
        assertTrue(host.contains("if (webView.parent != null || webView.isAttachedToWindow) return null"))
        assertTrue("sheet opening mid-capture", host.contains("if (webView.parent !== host)"))
    }

    @Test
    fun `the host is private, unfocusable, bounded and released when idle`() {
        assertTrue(host.contains("DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY"))
        assertTrue("no public flag", !host.contains("VIRTUAL_DISPLAY_FLAG_PUBLIC"))
        assertTrue("IME must never rise on the real screen", host.contains("FLAG_NOT_FOCUSABLE"))
        assertTrue(host.contains("const val IDLE_RELEASE_MS = 15_000L"))
        assertTrue(host.contains("main.postDelayed(releaseTask, IDLE_RELEASE_MS)"))
        assertTrue(host.contains("w > MAX_EDGE_PX || h > MAX_EDGE_PX"))
        assertTrue("circuit breaker", host.contains("disabled = reason"))
        assertEquals("pixel copy runs off the main thread", 1,
            Regex("""withContext\(Dispatchers\.Default\) \{ toBitmap""").findAll(host).count())
    }
}
