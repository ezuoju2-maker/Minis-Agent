package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePauseLogicTest {

    private class Player {
        val queue = ArrayDeque<String>()
        val held = mutableListOf<String>()
        var paused = false
        val spoken = mutableListOf<String>()

        fun enqueue(s: String) = queue.addLast(s)

        fun drainToSpeaker() {
            while (queue.isNotEmpty()) spoken.add(queue.removeFirst())
        }

        fun suspendForCapture() {
            if (paused) return
            paused = true
            while (queue.isNotEmpty()) held.add(queue.removeFirst())
        }

        fun resumeAfterCapture() {
            if (!paused) return
            paused = false
            val toSpeak = held.toList()
            held.clear()
            toSpeak.forEach { enqueue(it) }
        }

        fun stop() {
            paused = false
            held.clear()
            queue.clear()
        }
    }

    private fun shouldResume(prev: RecognitionState, next: RecognitionState): Boolean =
        next == RecognitionState.IDLE && prev != RecognitionState.IDLE

    @Test
    fun `unspoken utterances survive a capture and are replayed after it`() {
        val p = Player()
        p.enqueue("first sentence")
        p.enqueue("second sentence")

        p.suspendForCapture()
        assertTrue("queue must be emptied while the mic is open", p.queue.isEmpty())
        assertEquals(2, p.held.size)

        p.resumeAfterCapture()
        p.drainToSpeaker()
        assertEquals(listOf("first sentence", "second sentence"), p.spoken)
    }

    @Test
    fun `resume without a preceding suspend is a no-op`() {
        val p = Player()
        p.enqueue("x")
        p.resumeAfterCapture()
        p.drainToSpeaker()
        assertEquals(listOf("x"), p.spoken)
        assertTrue(p.held.isEmpty())
    }

    @Test
    fun `suspending twice does not lose the first batch`() {
        val p = Player()
        p.enqueue("a")
        p.suspendForCapture()
        p.enqueue("b")
        p.suspendForCapture()

        p.resumeAfterCapture()
        p.drainToSpeaker()
        assertTrue("the first batch must survive", "a" in p.spoken)
    }

    @Test
    fun `resuming twice does not duplicate utterances`() {
        val p = Player()
        p.enqueue("only once")
        p.suspendForCapture()
        p.resumeAfterCapture()
        p.resumeAfterCapture()
        p.drainToSpeaker()
        assertEquals(listOf("only once"), p.spoken)
    }

    @Test
    fun `stop discards held utterances so they never resurface`() {
        val p = Player()
        p.enqueue("stale")
        p.suspendForCapture()
        p.stop()
        p.resumeAfterCapture()
        p.drainToSpeaker()
        assertTrue("a stopped player must stay silent", p.spoken.isEmpty())
        assertFalse(p.paused)
    }

    @Test
    fun `all capture-ending transitions resume playback`() {
        assertTrue(shouldResume(RecognitionState.FINISHING, RecognitionState.IDLE))
        assertTrue(shouldResume(RecognitionState.RECORDING, RecognitionState.IDLE))
        assertTrue(shouldResume(RecognitionState.STARTING, RecognitionState.IDLE))
    }

    @Test
    fun `finishing does not resume while the mic may still be hot`() {
        assertFalse(shouldResume(RecognitionState.RECORDING, RecognitionState.FINISHING))
        assertFalse(shouldResume(RecognitionState.STARTING, RecognitionState.RECORDING))
    }

    @Test
    fun `an idle to idle transition does not resume`() {
        assertFalse(shouldResume(RecognitionState.IDLE, RecognitionState.IDLE))
    }
}
