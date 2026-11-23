package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadCaptureWindowTest {

    private val rate = 48_000
    private val frame = 512

    @Test
    fun `one second of tail is about 93 frames at 48k-512`() {
        assertEquals(93, VadCaptureWindow.tailFrames(rate, frame, 1.0f))
    }

    @Test
    fun `tail scales with the requested seconds`() {
        assertEquals(0, VadCaptureWindow.tailFrames(rate, frame, 0f))
        assertEquals(46, VadCaptureWindow.tailFrames(rate, frame, 0.5f))
        assertEquals(187, VadCaptureWindow.tailFrames(rate, frame, 2.0f))
    }

    @Test
    fun `preroll capacity is one second of PCM16 mono`() {
        assertEquals(96_000, VadCaptureWindow.prerollCapacityBytes(rate, 1.0f))
        assertEquals(48_000, VadCaptureWindow.prerollCapacityBytes(rate, 0.5f))
    }

    @Test
    fun `a flush must exceed the confirmed span, or the preroll never filled`() {
        val confirmed = 0.999f
        assertTrue(VadCaptureWindow.expectedFlushSeconds(1.0f, confirmed) > confirmed + 0.5f)
        assertEquals(confirmed, VadCaptureWindow.expectedFlushSeconds(0f, confirmed), 0.0001f)
    }
}
