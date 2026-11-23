package com.openminis.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.MonotonicFrameClock
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackPulseResidueTest {

    private class FakeAlpha : PulseAlpha {
        override var value = 0f
        val writes = mutableListOf<Float>()
        override suspend fun animateTo(target: Float) {
            delay(FALLBACK_PULSE_HALF_MS / 2L)
            value = (value + target) / 2f
            delay(FALLBACK_PULSE_HALF_MS / 2L)
            value = target
            writes += target
        }
        override suspend fun snapTo(target: Float) {
            value = target
            writes += target
        }
    }

    @Test
    fun `fail then auto-switch pulses three times and ends with no tint`() = runTest {
        val alpha = FakeAlpha()
        runFallbackPulse(current = 1, baseline = 0, alpha = alpha)
        assertEquals(listOf(1f, 0f, 1f, 0f, 1f, 0f), alpha.writes)
        assertEquals(0f, alpha.value)
    }

    @Test
    fun `a pulse cancelled mid-flash is cleared by the next run even without a new fallback`() = runTest {
        val alpha = FakeAlpha()
        val pulse = launch { runFallbackPulse(current = 1, baseline = 0, alpha = alpha) }
        advanceTimeBy(FALLBACK_PULSE_HALF_MS / 2L + 1)
        pulse.cancel()
        pulse.join()
        assertTrue("precondition: the cancelled pulse left a tint", alpha.value > 0f)

        runFallbackPulse(current = 0, baseline = 0, alpha = alpha)
        assertEquals(0f, alpha.value)
    }

    @Test
    fun `fail on chat A then open chat B shows no tint`() = runTest {
        val chatB = FakeAlpha()
        runFallbackPulse(current = 0, baseline = 0, alpha = chatB)
        assertEquals(0f, chatB.value)
        assertEquals(emptyList<Float>(), chatB.writes)
    }

    @Test
    fun `coming back to a chat that already fell back does not replay or keep a tint`() = runTest {
        val alpha = FakeAlpha().apply { value = 0.6f }
        runFallbackPulse(current = 2, baseline = 2, alpha = alpha)
        assertEquals(listOf(0f), alpha.writes)
        assertEquals(0f, alpha.value)
    }

    @Test
    fun `a second fallback during a pulse restarts it and still ends with no tint`() = runTest {
        val alpha = FakeAlpha()
        val first = launch { runFallbackPulse(current = 1, baseline = 0, alpha = alpha) }
        advanceTimeBy(FALLBACK_PULSE_HALF_MS.toLong() + FALLBACK_PULSE_HALF_MS / 2L)
        first.cancel()
        first.join()
        runFallbackPulse(current = 2, baseline = 0, alpha = alpha)
        assertEquals(0f, alpha.value)
    }

    @Test
    fun `real Animatable - cancelled pulse is reset by the next run`() = runTest {
        val clock = object : MonotonicFrameClock {
            var now = 0L
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                delay(16)
                now += 16_000_000L
                return onFrame(now)
            }
        }
        val anim = Animatable(0f)
        withContext(clock) {
            val pulse = launch { runFallbackPulse(current = 1, baseline = 0, alpha = anim.asPulseAlpha()) }
            advanceTimeBy(200)
            pulse.cancel()
            pulse.join()
            assertTrue("precondition: stopped mid-fade, got ${anim.value}", anim.value > 0f && anim.value < 1f)

            runFallbackPulse(current = 0, baseline = 0, alpha = anim.asPulseAlpha())
            assertEquals(0f, anim.value)

            runFallbackPulse(current = 1, baseline = 0, alpha = anim.asPulseAlpha())
            assertEquals(0f, anim.value)
        }
    }

    @Test
    fun `chat screen keys the pulse on the view model`() {
        val src = File("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt").readText()
        assertTrue(src.contains("val fallbackPulseAlpha = remember(viewModel) {"))
        assertTrue(src.contains("LaunchedEffect(viewModel, fallbackTrigger) {"))
        assertTrue(src.contains("runFallbackPulse(fallbackTrigger, fallbackPulseBaseline, fallbackPulseAlpha.asPulseAlpha())"))
        val title = src.substringAfter("val fallbackTrigger by viewModel.fallbackTrigger.collectAsState()")
            .substringBefore(".background(Color.Red")
        assertTrue("alpha must not be animated outside runFallbackPulse", !title.contains("fallbackPulseAlpha.animateTo("))
    }
}
