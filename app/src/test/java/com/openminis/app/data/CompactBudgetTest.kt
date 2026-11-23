package com.openminis.app.data

import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactBudgetTest {

    @Test
    fun `idle timeout is the working limit and total is only a backstop`() {
        assertTrue(
            "idle timeout must fire long before the total ceiling",
            ChatViewModel.COMPACT_IDLE_TIMEOUT_MS < ChatViewModel.COMPACT_MAX_TOTAL_MS,
        )
    }

    @Test
    fun `idle timeout lands before the providers' socket read timeout`() {
        val providerReadTimeoutMs = 10 * 60 * 1000L
        assertTrue(
            "idle timeout (${ChatViewModel.COMPACT_IDLE_TIMEOUT_MS}ms) must be under " +
                "the provider read timeout (${providerReadTimeoutMs}ms)",
            ChatViewModel.COMPACT_IDLE_TIMEOUT_MS < providerReadTimeoutMs,
        )
    }

    @Test
    fun `the reported regression would now succeed`() {
        val reportedRunMs = 150_179L
        val largestObservedGapMs = 2_400L
        assertTrue(
            "a continuously-streaming run must not trip the idle timer",
            largestObservedGapMs < ChatViewModel.COMPACT_IDLE_TIMEOUT_MS,
        )
        assertTrue(
            "and it must finish well inside the total ceiling",
            reportedRunMs < ChatViewModel.COMPACT_MAX_TOTAL_MS,
        )
    }

    @Test
    fun `total ceiling is the 15 minute backstop`() {
        assertEquals(15 * 60 * 1000L, ChatViewModel.COMPACT_MAX_TOTAL_MS)
    }

    @Test
    fun `a silent stream is not retried by splitting`() {
        assertTrue(
            "idle timeout must not trigger the split retry",
            !ChatViewModel.shouldSplitOnError(
                ChatViewModel.Companion.CompactIdleTimeoutException("no data for 120s")
            ),
        )
    }

    @Test
    fun `call budget is well under what the depth cap alone permits`() {
        val depthCapWorstCase = 1 + 2 + 4 + 8
        assertTrue(
            "call budget must materially cut the depth-cap worst case",
            ChatViewModel.MAX_COMPACT_LLM_CALLS < depthCapWorstCase,
        )
        assertTrue(
            "budget must still allow a first split plus a rescue",
            ChatViewModel.MAX_COMPACT_LLM_CALLS >= 5,
        )
    }

    @Test
    fun `worst case is still bounded, just generously`() {
        val worstMs = ChatViewModel.COMPACT_MAX_TOTAL_MS
        assertTrue("must stay bounded", worstMs <= 20 * 60 * 1000L)
    }
}
