package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicMaxTokensTest {


    private val MIN_MAX_TOKENS = 1024
    private val GLOBAL_MAX_TOKENS_CEILING = 128_000
    private val maxInLoopCompactions = 3

    private fun dynamicMaxTokens(modelMaxOutput: Int, contextWindow: Int, lastContextTokens: Int): Int {
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, modelMaxOutput)
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        return minOf(maxOutputCeiling, clamped)
    }

    private fun contextTokensFrom(usage: LLMUsage, previous: Int): Int = when {
        usage.latestContextTokens > 0 -> usage.latestContextTokens
        usage.inputTokens > 0 -> usage.inputTokens + (usage.cacheReadInputTokens ?: 0) + (usage.cacheCreationInputTokens ?: 0)
        else -> previous
    }

    private enum class Action { PROCEED, COMPACTED, STOP }

    private class InLoopGuard(private val policy: ContextPolicy, private val window: Int) {
        var measured = 0
        var compactions = 0
        var noProgress = false
        var compactTo: (Int) -> Int = { it }
        fun check(): Action {
            val tokens = measured
            if (tokens <= 0) return Action.PROCEED
            return when (policy.check(tokens, window)) {
                ContextPolicy.CheckResult.OK -> Action.PROCEED
                ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                    if (compactions >= 3 || noProgress) {
                        return if (tokens < window) Action.PROCEED else Action.STOP
                    }
                    compactions += 1
                    measured = compactTo(tokens)
                    noProgress = measured >= tokens
                    Action.COMPACTED
                }
                ContextPolicy.CheckResult.EXHAUSTED -> Action.STOP
            }
        }
    }


    @Test
    fun `input past the window is NEEDS_COMPACT before any max_tokens is computed - never a 1024 send`() {
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forUserCap(128_000).check(130_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forContextWindow(128_000).check(130_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forContextWindow(128_000).check(128_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, ContextPolicy.forContextWindow(16_000).check(16_500, 16_000))

        assertEquals(MIN_MAX_TOKENS, dynamicMaxTokens(8192, 128_000, 130_000))
        val g = InLoopGuard(ContextPolicy.forUserCap(128_000), 128_000)
        g.measured = 130_000
        g.compactTo = { it - 500 }
        assertEquals(Action.COMPACTED, g.check())
        assertEquals(Action.COMPACTED, g.check())
        assertEquals(Action.COMPACTED, g.check())
        assertEquals(Action.STOP, g.check())
        assertEquals(3, g.compactions)
    }

    @Test
    fun `dynamicMaxTokens shrinks with the input and honours both ceilings`() {
        assertEquals(8192, dynamicMaxTokens(8192, 128_000, 10_000))
        assertEquals(4_000, dynamicMaxTokens(8192, 128_000, 124_000))
        assertEquals(GLOBAL_MAX_TOKENS_CEILING, dynamicMaxTokens(1_000_000, 2_000_000, 0))
        assertEquals(8192, dynamicMaxTokens(8192, 0, 0))
    }


    @Test
    fun `a report above the local estimate still decides - calibration reproduces it`() {
        val localEstimate = 60_000
        val reported = contextTokensFrom(LLMUsage(inputTokens = 5_000, outputTokens = 10, latestContextTokens = 120_000), previous = 0)
        assertEquals(120_000, reported)
        assertTrue(reported > localEstimate)
        val ratio = ContextSizeMeter.calibrationRatio(reported, localEstimate)!!
        val judged = ContextSizeMeter.calibrated(localEstimate, ratio)
        assertEquals(reported, judged)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forUserCap(128_000).check(judged, 128_000))
        assertEquals("the uncalibrated estimate alone would have let it through",
            ContextPolicy.CheckResult.OK, ContextPolicy.forUserCap(128_000).check(localEstimate, 128_000))

        val viaCache = contextTokensFrom(LLMUsage(inputTokens = 50_000, outputTokens = 1, cacheReadInputTokens = 70_000), previous = 0)
        assertEquals(120_000, viaCache)
        assertEquals(99, contextTokensFrom(LLMUsage(inputTokens = 0, outputTokens = 5), previous = 99))
    }


    @Test
    fun `the 5th tool iteration crossing 85 percent compacts mid-loop and the next check measures the result`() {
        val window = 128_000
        val g = InLoopGuard(ContextPolicy.forUserCap(window), window)
        g.compactTo = { 40_000 }
        val readings = listOf(60_000, 72_000, 85_000, 100_000, 110_000)
        val actions = readings.map { g.measured = it; g.check() }
        assertEquals(listOf(Action.PROCEED, Action.PROCEED, Action.PROCEED, Action.PROCEED, Action.COMPACTED), actions)
        assertEquals(40_000, g.measured)
        assertEquals(Action.PROCEED, g.check())
        assertEquals(1, g.compactions)
    }


    @Test
    fun `a 32k cap on a 1M model compacts at 27200`() {
        val p = ContextPolicy.forUserCap(32_000)
        assertEquals(27_200, p.compactThreshold)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(27_200, 32_000))
        assertEquals(ContextPolicy.CheckResult.OK, p.check(27_199, 32_000))
    }


    @Test
    fun `drift guard - guard precedes the provider call and the clamp still warns`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("private const val MIN_MAX_TOKENS = 1024"))
        assertTrue(vm.contains("private const val GLOBAL_MAX_TOKENS_CEILING = 128_000"))
        assertTrue(vm.contains("private val maxInLoopCompactions = 3"))
        assertTrue(vm.contains("val clamped = maxOf(remaining, MIN_MAX_TOKENS)"))
        assertTrue("overflow branch removed?", vm.contains("if (remaining <= 0) {"))
        val loop = vm.substring(vm.indexOf("suspend fun runAgentLoop"))
        val check = loop.indexOf("when (inLoopContextCheck(inLoopCompactions))")
        val send = loop.indexOf("currentProvider.streamMessage(")
        assertTrue("in-loop guard must run before the provider call", check in 1 until send)
        assertTrue(loop.contains("dynamicMaxTokens(currentProvider, dispatchInputTokens)"))
        assertTrue(vm.contains("lastContextTokens = chunk.usage.latestContextTokens"))
        assertTrue(vm.contains("_lastTurnContextTokens.value = lastContextTokens"))
        assertTrue(vm.contains("calibrateContextSize(lastContextTokens)"))
        assertEquals(2, vm.split("val m = contextMeasurement()\n        val tokens = m.measured").size - 1)
        assertTrue(!vm.contains("val tokens = _lastTurnContextTokens.value"))
        assertTrue(!vm.contains("private var lastTurnContextTokensStale"))
        val policy = ProductionSources.read("data/ContextPolicy.kt")
        assertTrue(policy.contains("if (contextWindow > 0 && estimatedTokens >= contextWindow) {"))
    }
}
