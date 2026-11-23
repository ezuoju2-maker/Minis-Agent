package com.openminis.app.ui.webview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RenderProcessGoneCoverageTest {

    private val mainSrc = File("src/main/java/com/openminis/app")

    private fun kotlinSources(): List<File> {
        assertTrue("source root not found (cwd=${File(".").absolutePath})", mainSrc.isDirectory)
        return mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `every WebViewClient overrides onRenderProcessGone`() {
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            val text = f.readText()
            val clients = Regex("""object\s*:\s*WebViewClient\(\)""").findAll(text).count()
            if (clients == 0) continue
            val overrides = Regex("""override\s+fun\s+onRenderProcessGone""").findAll(text).count()
            if (overrides < clients) {
                offenders += "${f.name}: $clients WebViewClient(s), $overrides override(s)"
            }
        }
        assertTrue(
            "these files define a WebViewClient without onRenderProcessGone — a dead " +
                "renderer will take the whole app down: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no bare WebViewClient instance is installed`() {
        val offenders = kotlinSources().filter { f ->
            Regex("""webViewClient\s*=\s*WebViewClient\(\)""").containsMatchIn(f.readText())
        }.map { it.name }
        assertTrue(
            "a bare `WebViewClient()` cannot override onRenderProcessGone — use " +
                "`object : WebViewClient() { … }`: $offenders",
            offenders.isEmpty(),
        )
    }
}
