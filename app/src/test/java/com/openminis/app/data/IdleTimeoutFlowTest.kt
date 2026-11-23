package com.openminis.app.data

import com.openminis.app.provider.withIdleTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleTimeoutFlowTest {

    @Test
    fun `a slow but continuous stream survives far past the idle limit`() = runTest {
        val idleMs = 100_000L
        val received = flow {
            repeat(600) {
                delay(1_000)
                emit(it)
            }
        }.withIdleTimeout(idleMs) { fail("healthy stream must not time out") }
            .toList()

        assertEquals(600, received.size)
        assertTrue(
            "elapsed must exceed the idle limit — that is the point",
            testScheduler.currentTime > idleMs,
        )
    }

    @Test
    fun `a stream that goes quiet trips the timer`() = runTest {
        val idleMs = 30_000L
        var timedOutAfter: Long? = null

        try {
            flow {
                emit(1)
                emit(2)
                delay(idleMs * 2)
                emit(3)
            }.withIdleTimeout(idleMs) { timedOutAfter = it; throw IllegalStateException("idle") }
                .toList()
            fail("expected the idle timeout to throw")
        } catch (e: IllegalStateException) {
            assertEquals("idle", e.message)
        }

        assertEquals(idleMs, timedOutAfter)
    }

    @Test
    fun `the timer resets per chunk rather than accumulating`() = runTest {
        val idleMs = 10_000L
        val received = flow {
            repeat(5) {
                delay(idleMs - 1_000)
                emit(it)
            }
        }.withIdleTimeout(idleMs) { fail("gaps under the limit must not time out") }
            .toList()

        assertEquals(5, received.size)
        assertTrue("sum of gaps must exceed the limit", testScheduler.currentTime > idleMs)
    }

    @Test
    fun `an upstream error propagates instead of looking like a clean end`() = runTest {
        try {
            flow<Int> {
                emit(1)
                throw IllegalArgumentException("upstream boom")
            }.withIdleTimeout(60_000L) { fail("should not time out") }
                .toList()
            fail("expected the upstream error to propagate")
        } catch (e: IllegalArgumentException) {
            assertEquals("upstream boom", e.message)
        }
    }

    @Test
    fun `an empty stream completes normally`() = runTest {
        val received = flow<Int> { }
            .withIdleTimeout(60_000L) { fail("empty stream must not time out") }
            .toList()
        assertEquals(0, received.size)
    }
}
