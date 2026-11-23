package com.openminis.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayBitmapLimitsTest {

    private val bytesPerPixel = 4L

    private val canvasCeilingBytes = 100L * 1024 * 1024

    @Test
    fun `a square bitmap at the ceiling stays under the canvas limit`() {
        val edge = DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX.toLong()
        val worstCaseBytes = edge * edge * bytesPerPixel
        assertTrue(
            "worst-case bitmap is $worstCaseBytes bytes, canvas ceiling is $canvasCeilingBytes",
            worstCaseBytes < canvasCeilingBytes,
        )
    }

    @Test
    fun `the ceiling is within the universally supported max texture dimension`() {
        assertTrue(DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX <= 4096)
    }

    @Test
    fun `the crashing bitmap from the report would be rejected by the ceiling`() {
        val crashWidth = 3000
        val crashHeight = 17920
        assertTrue(
            "the reported image must exceed the ceiling, else the guard is a no-op for it",
            maxOf(crashWidth, crashHeight) > DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX,
        )

        val scale = DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX.toDouble() / crashHeight
        val cappedW = (crashWidth * scale).toLong().coerceAtLeast(1)
        val cappedH = DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX.toLong()
        val cappedBytes = cappedW * cappedH * bytesPerPixel
        assertTrue(
            "capped bitmap is $cappedBytes bytes (${cappedW}x$cappedH)",
            cappedBytes < canvasCeilingBytes,
        )
    }

    @Test
    fun `an image already under the ceiling is unaffected`() {
        val w = 1600
        val h = 1200
        assertTrue(maxOf(w, h) <= DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
    }


    @Test
    fun `a phone screenshot is sampled down to the thumbnail bound`() {
        val s = DisplayBitmapLimits.sampleSizeFor(1080, 2400, DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX)
        assertEquals(8, s)
        val bytes = (1080L / s) * (2400L / s) * bytesPerPixel
        assertTrue("sampled thumbnail is $bytes bytes", bytes < 1L * 1024 * 1024)
    }

    @Test
    fun `sampling never leaves the longest edge above the bound`() {
        for ((w, h) in listOf(1 to 1, 512 to 512, 513 to 100, 1080 to 2400, 3000 to 17920, 8192 to 8192)) {
            val s = DisplayBitmapLimits.sampleSizeFor(w, h, DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX)
            assertTrue(
                "${w}x$h sample=$s -> ${w / s}x${h / s} exceeds the bound",
                maxOf(w / s, h / s) <= DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX,
            )
        }
    }

    @Test
    fun `sampling is always a power of two and never upsamples`() {
        assertEquals(1, DisplayBitmapLimits.sampleSizeFor(100, 100, 512))
        assertEquals(1, DisplayBitmapLimits.sampleSizeFor(512, 512, 512))
        assertEquals(2, DisplayBitmapLimits.sampleSizeFor(513, 10, 512))
        val s = DisplayBitmapLimits.sampleSizeFor(3000, 17920, DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
        assertTrue("sample=$s must be a power of two", s > 0 && (s and (s - 1)) == 0)
    }

    @Test
    fun `degenerate inputs fall back to an unsampled decode`() {
        assertEquals(1, DisplayBitmapLimits.sampleSizeFor(0, 2400, 512))
        assertEquals(1, DisplayBitmapLimits.sampleSizeFor(1080, 0, 512))
        assertEquals(1, DisplayBitmapLimits.sampleSizeFor(1080, 2400, 0))
    }

    @Test
    fun `the thumbnail bound is well under the display ceiling`() {
        assertTrue(DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX < DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX)
    }
}
