package com.openminis.app.speech

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Review0923VoiceTerminalCallbackTest {

    private val src by lazy { ProductionSources.read("speech/ProviderSpeechRecognitionEngine.kt") }

    @Test
    fun `a manual stop with a live session always resolves to a terminal outcome`() {
        for (held in listOf(0f, 0.3f, 0.31f, 5f)) {
            val o = VadManualStopOutcome.decide(hasSession = true, heldSeconds = held, floorSeconds = 0.3f)
            assertTrue("held=$held gave $o", o == VadManualStopOutcome.TRANSCRIBE || o == VadManualStopOutcome.NO_MATCH)
        }
        assertEquals(
            VadManualStopOutcome.NOTHING_TO_DO,
            VadManualStopOutcome.decide(hasSession = false, heldSeconds = 5f, floorSeconds = 0.3f),
        )
    }

    @Test
    fun `the manual-stop flush is wired into stop and into the session-limit callback`() {
        val stopBody = src.substringAfter("override fun stop() {").substringBefore("\n    }")
        assertTrue("stop() must flush the VAD take", stopBody.contains("flushVadOnManualStop()"))
        assertTrue("stop() must collect the OPEN segment first", stopBody.contains(".flush()"))
        val limitBody = src.substringAfter("override fun onSessionLimit(").substringBefore("override fun onCaptureError")
        assertTrue("session limit must end in a terminal callback", limitBody.contains("flushVadOnManualStop()"))
    }

    @Test
    fun `settling the mic after a held take never leaves the manager without a terminal callback`() {
        val holdBlock = src.substringAfter("holdFlushJob = scope.launch {").substringBefore("return\n")
        assertTrue("hold-expiry block not found", holdBlock.contains("stopVad()"))
        val unconditional = holdBlock.replace(
            Regex("if \\(held > TOO_SHORT_TOAST_FLOOR\\) \\{[\\s\\S]*?\\n\\s*\\}"),
            "",
        )
        val holdAlwaysTerminates = unconditional.contains("onError(") ||
            unconditional.contains("onFinal(") ||
            unconditional.contains("flushVadOnManualStop()")

        val stopBody = src.substringAfter("override fun stop() {").substringBefore("\n    }")
        val stopFlushesOnSession = Regex("wasVad\\s*=.*vadSession").containsMatchIn(stopBody)

        assertTrue(
            "hold-expiry calls stopVad() and, for a take <= TOO_SHORT_TOAST_FLOOR, delivers no " +
                "onFinal/onError; stop() then skips the flush because detector is null — the " +
                "panel hangs on 'Recognizing...'",
            holdAlwaysTerminates || stopFlushesOnSession,
        )
    }

    @Test
    fun `a take that already resolved cannot be finalised a second time`() {
        val silence = src.substringAfter("if (reason == SegmentEndReason.SILENCE_DETECTED) {\n")
            .substringBefore("transcribeJob = scope.launch")
        assertTrue(silence.contains("vadSession = null"))
        val cancelBody = src.substringAfter("override fun cancel() {").substringBefore("\n    }")
        assertTrue(cancelBody.contains("vadSession = null"))
    }
}
