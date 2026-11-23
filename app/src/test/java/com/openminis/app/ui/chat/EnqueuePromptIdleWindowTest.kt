package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnqueuePromptIdleWindowTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Funnel {
        var isStreaming = false
        val queue = ArrayDeque<String>()
        val delivered = mutableListOf<String>()
        var drainCount = 0

        fun enqueuePrompt(text: String): Boolean {
            if (text.isBlank() || !isStreaming) return false
            queue += text
            return true
        }

        fun drain() {
            drainCount++
            while (queue.isNotEmpty()) delivered += queue.removeFirst()
        }
    }

    private suspend fun runTurn(
        f: Funnel,
        duringLoop: suspend () -> Unit = {},
        afterFlagCleared: suspend () -> Unit = {},
    ) {
        f.isStreaming = true
        duringLoop()
        yield()
        f.drain()
        f.isStreaming = false
        afterFlagCleared()
    }

    @Test
    fun `prompt enqueued mid-stream is drained by the epilogue`() = runTest(dispatcher) {
        val f = Funnel()
        runTurn(f, duringLoop = { assertTrue(f.enqueuePrompt("mid")) })
        assertEquals("the mid-stream prompt must be delivered", listOf("mid"), f.delivered)
        assertTrue("queue must be empty after the drain", f.queue.isEmpty())
    }

    @Test
    fun `prompt arriving after the flag clears is rejected, not stranded`() = runTest(dispatcher) {
        val f = Funnel()
        var accepted = true
        runTurn(f, afterFlagCleared = { accepted = f.enqueuePrompt("late") })
        assertEquals(
            "enqueuePrompt must REJECT once idle — accepting here is exactly the " +
                "iOS evaporation shape, since no further drain is scheduled",
            false,
            accepted,
        )
        assertTrue("nothing may be left sitting in the queue", f.queue.isEmpty())
        assertEquals("and nothing may be silently swallowed", emptyList<String>(), f.delivered)
    }

    @Test
    fun `high frequency enqueue across the boundary strands nothing`() = runTest(dispatcher) {
        val f = Funnel()
        var accepted = 0
        var rejected = 0

        val hammer = launch {
            repeat(200) {
                if (f.enqueuePrompt("p$it")) accepted++ else rejected++
                yield()
            }
        }
        repeat(20) { runTurn(f) }
        hammer.join()
        f.drain()

        assertEquals(
            "every ACCEPTED prompt must have been delivered — a shortfall is the " +
                "evaporation race (accepted=$accepted delivered=${f.delivered.size})",
            accepted,
            f.delivered.size,
        )
        assertEquals(
            "accepted + rejected must account for every attempt",
            200,
            accepted + rejected,
        )
        assertTrue("some attempts must have landed mid-stream for this to be meaningful", accepted > 0)
    }

    @Test
    fun `ios-shaped ordering does strand a late prompt`() = runTest(dispatcher) {
        val f = Funnel()
        f.isStreaming = true
        f.drain()
        f.isStreaming = false
        f.queue += "late"
        assertTrue(
            "the counter-model must strand the prompt — otherwise this suite " +
                "cannot detect the race it claims to rule out",
            f.queue.isNotEmpty() && f.delivered.isEmpty(),
        )
    }
}
