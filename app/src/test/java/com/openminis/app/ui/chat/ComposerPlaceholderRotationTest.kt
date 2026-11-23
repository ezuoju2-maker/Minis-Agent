package com.openminis.app.ui.chat

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerPlaceholderRotationTest {

    private val rng = Random(20260826)
    private fun realRandom(bound: Int) = rng.nextInt(bound)

    private fun next(
        current: Int,
        hasFocusedBefore: Boolean = true,
        sessionHasMessages: Boolean = true,
        screenReaderOn: Boolean = false,
        randomIndex: (Int) -> Int = ::realRandom,
    ) = ComposerPlaceholderRotation.nextIndex(
        current = current,
        hasFocusedBefore = hasFocusedBefore,
        sessionHasMessages = sessionHasMessages,
        screenReaderOn = screenReaderOn,
        randomIndex = randomIndex,
    )


    @Test
    fun `empty session keeps the default on first focus`() {
        repeat(5000) {
            val result = next(
                current = ComposerPlaceholderRotation.DEFAULT_INDEX,
                hasFocusedBefore = false,
                sessionHasMessages = false,
            )
            assertEquals(ComposerPlaceholderRotation.DEFAULT_INDEX, result)
        }
    }

    @Test
    fun `non-empty session rotates on first focus`() {
        repeat(5000) {
            val result = next(
                current = ComposerPlaceholderRotation.DEFAULT_INDEX,
                hasFocusedBefore = false,
                sessionHasMessages = true,
            )
            assertNotEquals(ComposerPlaceholderRotation.DEFAULT_INDEX, result)
        }
    }

    @Test
    fun `empty session rotates on subsequent focuses`() {
        val result = next(
            current = ComposerPlaceholderRotation.DEFAULT_INDEX,
            hasFocusedBefore = true,
            sessionHasMessages = false,
        )
        assertNotEquals(ComposerPlaceholderRotation.DEFAULT_INDEX, result)
    }


    @Test
    fun `rotation never lands on the entry already displayed`() {
        var current = ComposerPlaceholderRotation.DEFAULT_INDEX
        repeat(40_000) {
            val nextIdx = next(current)
            assertNotEquals("repeat at index $current", current, nextIdx)
            current = nextIdx
        }
    }

    @Test
    fun `result always lands inside the pool`() {
        var current = 0
        repeat(40_000) {
            val nextIdx = next(current)
            assertTrue(
                "index $nextIdx out of range",
                nextIdx >= 0 && nextIdx < ComposerPlaceholderRotation.POOL_SIZE,
            )
            current = nextIdx
        }
    }

    @Test
    fun `every pool entry is reachable including the default`() {
        val seen = mutableSetOf<Int>()
        var current = ComposerPlaceholderRotation.DEFAULT_INDEX
        repeat(40_000) {
            current = next(current)
            seen.add(current)
        }
        assertEquals(
            (0 until ComposerPlaceholderRotation.POOL_SIZE).toSet(),
            seen,
        )
    }

    @Test
    fun `distribution covers every alternative from each starting point`() {
        for (start in 0 until ComposerPlaceholderRotation.POOL_SIZE) {
            val seen = mutableSetOf<Int>()
            repeat(5000) { seen.add(next(start)) }
            val expected = (0 until ComposerPlaceholderRotation.POOL_SIZE).toSet() - start
            assertEquals("from index $start", expected, seen)
        }
    }


    @Test
    fun `screen reader pins the current entry`() {
        for (start in 0 until ComposerPlaceholderRotation.POOL_SIZE) {
            repeat(1000) {
                assertEquals(start, next(start, screenReaderOn = true))
            }
        }
    }

    @Test
    fun `screen reader pin outranks the first-focus rotation`() {
        assertEquals(
            2,
            next(
                current = 2,
                hasFocusedBefore = false,
                sessionHasMessages = true,
                screenReaderOn = true,
            ),
        )
    }


    @Test
    fun `draw below current maps to itself`() {
        assertEquals(1, next(current = 3) { 1 })
    }

    @Test
    fun `draw at or above current shifts up by one to skip it`() {
        assertEquals(2, next(current = 1) { 1 })
        assertEquals(1, next(current = 0) { 0 })
    }

    @Test
    fun `top of the draw range reaches the last pool entry`() {
        val maxDraw = ComposerPlaceholderRotation.POOL_SIZE - 2
        assertEquals(
            ComposerPlaceholderRotation.POOL_SIZE - 1,
            next(current = 0) { maxDraw },
        )
    }
}
