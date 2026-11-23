package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContextOverflowRejectionParityTest {

    private val repoRoot: File by lazy {
        var dir: File? = ProductionSources.mainRoot()
        while (dir != null && !File(dir, "src/ios").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "repo root with src/ios not found" }
    }

    private fun iosMarkers(): List<String> {
        val src = File(repoRoot, "src/ios/Agent/Chat/ContextPolicy.swift").readText()
        val start = src.indexOf("static let overflowMarkers = [")
        require(start >= 0) { "iOS overflowMarkers not found" }
        val body = src.substring(start, src.indexOf(']', start))
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `iOS marker list was read`() {
        val markers = iosMarkers()
        assertTrue("expected a non-trivial list, got $markers", markers.size >= 10)
        assertTrue(markers.contains("prompt is too long"))
    }

    @Test
    fun `every iOS overflow wording is an overflow on Android too`() {
        val missing = iosMarkers().filterNot { ContextOverflowGuard.isContextOverflow(400, "[400] $it") }
        assertEquals("iOS markers Android does not recognise", emptyList<String>(), missing)
    }

    @Test
    fun `OpenMinis 133 wording on an HTTP 400 is an overflow`() {
        val openAi = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(
            "mapHttpError format changed — update this test",
            openAi.contains("\"[\$statusCode] \$errorMessage\""),
        )
        assertTrue(
            ContextOverflowGuard.isContextOverflow(
                400,
                "[400] Your input exceeds the context window of this model. Please adjust your input and try again.",
            ),
        )
    }

    @Test
    fun `a Responses response_failed context_length_exceeded is an overflow`() {
        val openAi = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        val statusLess = openAi.contains("throw LLMError.ProviderError(\"[\$code] \$message\")\n")
        val status: Int? = if (statusLess) null else 400
        assertTrue(
            "response.failed context_length_exceeded (status=$status) is not recognised",
            ContextOverflowGuard.isContextOverflow(
                status,
                "[context_length_exceeded] Your input exceeds the context window of this model.",
            ),
        )
    }

    @Test
    fun `Anthropic input plus max_tokens over the limit is an overflow`() {
        assertTrue(
            ContextOverflowGuard.isContextOverflow(
                400,
                "[invalid_request_error] input length and `max_tokens` exceed context limit: " +
                    "188240 + 21333 > 200000, decrease input length or `max_tokens` and try again",
            ),
        )
    }

    @Test
    fun `a per-minute token rate limit is still not an overflow`() {
        assertFalse(ContextOverflowGuard.isContextOverflow(429, "too many tokens per minute"))
    }

    @Test
    fun `the loop prologue does not discard a rejection's spent valve`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(
            "noteContextOverflow no longer spends the valve — update this test",
            vm.contains("sentPastExtrapolatedLimitThisLoop = true\n        AppLogger.warning("),
        )
        val prologueReset = vm.contains(
            "lastInLoopCompactionMadeNoProgress = false\n        sentPastExtrapolatedLimitThisLoop = false",
        )
        assertFalse("runAgentLoop's prologue unconditionally re-arms the valve", prologueReset)
    }

    @Test
    fun `after a rejection the retry stops rather than re-sending`() {
        val window = 128_000
        val raw = 120_000
        val ratio = ContextSizeMeter.ratioAfterOverflow(1.0, raw, 156_000, window)
        val measured = ContextSizeMeter.calibrated(raw, ratio)
        val step = ContextPolicy.inLoopStep(
            verdict = ContextPolicy.CheckResult.NEEDS_COMPACT,
            measured = measured,
            rawTokens = raw,
            window = window,
            canCompact = false,
            ratio = ratio,
            uncalibratedSendUsed = true,
        )
        assertEquals(ContextPolicy.InLoopStep.STOP, step)
    }
}
