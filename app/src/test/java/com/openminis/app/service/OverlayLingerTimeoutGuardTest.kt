package com.openminis.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayLingerTimeoutGuardTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/openminis/app/service/AgentForegroundService.kt")
        assertTrue("AgentForegroundService.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        f.readText()
    }

    @Test
    fun `the completion linger is bounded by a timeout`() {
        assertTrue(
            "COMPLETION_LINGER_TIMEOUT_MS must exist — an unbounded linger now pins the FGS",
            source.contains("COMPLETION_LINGER_TIMEOUT_MS"),
        )
        assertTrue(
            "the timeout must be awaited before releasing the latch",
            Regex("""delay\(COMPLETION_LINGER_TIMEOUT_MS\)""").containsMatchIn(source),
        )
    }

    @Test
    fun `the timeout releases the tracker keep-alive`() {
        val job = source.substringAfter("delay(COMPLETION_LINGER_TIMEOUT_MS)").take(400)
        assertTrue(
            "expiring the linger must clear the tracker flag, or the service stays alive forever",
            job.contains("setOverlayCompletionPending(false)"),
        )
    }

    @Test
    fun `the linger timer is only cancelled while busy`() {
        val block = source.substringAfter("if (shouldShow) {").take(900)
        val cancelIdx = block.indexOf("lingerJob?.cancel()")
        assertTrue("no lingerJob cancel found inside shouldShow", cancelIdx >= 0)
        assertTrue(
            "the cancel inside shouldShow must be guarded by isBusy — an " +
                "unconditional cancel kills the timeout on the next emission",
            block.take(cancelIdx).contains("if (isBusy) {"),
        )
    }

    @Test
    fun `every latch clear is mirrored to a keep-alive release`() {
        val lines = source.lines()
        val offenders = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (!line.trim().startsWith("hasCompletionPending = false")) return@forEachIndexed
            if (line.contains("var hasCompletionPending")) return@forEachIndexed
            val window = lines.subList(
                maxOf(0, i - 6),
                minOf(lines.size, i + 30),
            ).filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
                .joinToString("\n")
            val releases = window.contains("setOverlayCompletionPending(false)") ||
                window.contains("dismissOverlay()")
            if (!releases) offenders += "line ${i + 1}: ${line.trim()}"
        }
        assertTrue(
            "these clears of hasCompletionPending do not release the tracker " +
                "keep-alive (directly or via dismissOverlay): $offenders",
            offenders.isEmpty(),
        )
    }
}
