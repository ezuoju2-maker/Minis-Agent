package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHotPathDiagnosticsTest {
    @Test fun `newest-row breadcrumbs fire once per row, not per frame or per token`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("if (perfNewestLogged.add(\"compose:\${item.key}\")) {\n                                    com.openminis.app.diagnostics.PerfLongCtx.step("))
        assertTrue(screen.contains("if (perfNewestLogged.add(\"placed:\${item.key}\")) {\n                                                com.openminis.app.diagnostics.PerfLongCtx.step("))
        val steps = Regex("PerfLongCtx\\.step\\(\\s*sessionId,\\s*\"lazyColumn\\.firstItem\\.").findAll(screen).count()
        assertTrue("exactly the two gated call sites", steps == 2)
    }

    @Test fun `Stop does not sleep on the caller's thread`() {
        val shell = ProductionSources.read("sandbox/FreshProcessShell.kt")
        val stop = shell.substringAfter("    fun stop() {").substringBefore("\n    }")
        assertTrue("the kill and its settle wait run on their own thread",
            stop.contains("kotlin.concurrent.thread(name = \"FreshShellStop\", isDaemon = true) {\n            killCommand(p, readEof = null)"))
    }
}
