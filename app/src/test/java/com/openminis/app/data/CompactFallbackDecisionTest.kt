package com.openminis.app.data

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactFallbackDecisionTest {

    private fun worthSwitching(
        error: Throwable,
        strategy: FallbackStrategy = FallbackStrategy.default,
    ): Boolean {
        val err = error as? LLMError
        return err?.isFallbackable == true ||
            err?.isHttpServerError == true ||
            error is LLMError.RateLimited ||
            strategy == FallbackStrategy.always
    }

    @Test
    fun `quota and auth failures switch models`() {
        assertTrue(worthSwitching(LLMError.RateLimited()))
        assertTrue(worthSwitching(LLMError.InvalidApiKey("401")))
        assertTrue(worthSwitching(LLMError.ProviderError("insufficient balance")))
    }

    @Test
    fun `a 5xx switches models`() {
        val e = LLMError.TransientError("upstream", httpStatus = 503)
        assertTrue("a confirmed 5xx is another model's problem to solve", worthSwitching(e))
    }

    @Test
    fun `a transient error with no status does not switch`() {
        val e = LLMError.TransientError("socket closed", httpStatus = null)
        assertFalse(worthSwitching(e))
    }

    @Test
    fun `always strategy switches on anything`() {
        val e = LLMError.TransientError("socket closed", httpStatus = null)
        assertFalse(worthSwitching(e, FallbackStrategy.default))
        assertTrue(worthSwitching(e, FallbackStrategy.always))
    }

    @Test
    fun `the splitter does not swallow fallbackable errors`() {
        assertFalse(ChatViewModelCompanionProbe.shouldSplit(LLMError.RateLimited()))
        assertFalse(ChatViewModelCompanionProbe.shouldSplit(LLMError.InvalidApiKey("401")))
        assertFalse(
            ChatViewModelCompanionProbe.shouldSplit(
                LLMError.TransientError("upstream", httpStatus = 503),
            ),
        )
        assertTrue(ChatViewModelCompanionProbe.shouldSplit(LLMError.ProviderError("too many tokens")))
    }

    private fun pick(available: List<String>, failed: Set<String>): String =
        available.firstOrNull { it !in failed } ?: available.first()

    @Test
    fun `group re-pick skips the member that just failed`() {
        assertEquals("B", pick(listOf("A", "B", "C"), setOf("A")))
    }

    @Test
    fun `a fully degraded group still resolves`() {
        assertEquals("A", pick(listOf("A", "B"), setOf("A", "B")))
    }
}

private object ChatViewModelCompanionProbe {
    fun shouldSplit(e: Throwable): Boolean =
        com.openminis.app.ui.chat.ChatViewModel.shouldSplitOnError(e)
}
