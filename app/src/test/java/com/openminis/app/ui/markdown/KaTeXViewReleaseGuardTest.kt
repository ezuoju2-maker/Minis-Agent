package com.openminis.app.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KaTeXViewReleaseGuardTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt")
        assertTrue(
            "KaTeXView.kt not found — this test locates sources relative to the " +
                "module dir (cwd=${File(".").absolutePath})",
            f.isFile,
        )
        f.readText()
    }

    @Test
    fun `the per-formula WebView is released through AndroidView onRelease`() {
        assertTrue(
            "KaTeXRenderView's AndroidView must pass an onRelease hook",
            Regex("""onRelease\s*=\s*\{""").containsMatchIn(source),
        )
    }

    @Test
    fun `onRelease destroys the WebView`() {
        val idx = Regex("""onRelease\s*=\s*\{""").find(source)?.range?.first ?: -1
        assertTrue("onRelease hook not present", idx >= 0)
        val body = source.substring(idx, minOf(source.length, idx + 600))
        assertTrue(
            "onRelease must call destroy() on the WebView it receives",
            body.contains(".destroy()"),
        )
    }

    @Test
    fun `a capture queued before release is skipped`() {
        assertTrue(source.contains("KATEX_WEBVIEW_RELEASED_TAG"))
        assertTrue(
            "the capture path must check the release marker before drawing",
            Regex("""tag\s*==\s*KATEX_WEBVIEW_RELEASED_TAG""").containsMatchIn(source),
        )
    }
}
