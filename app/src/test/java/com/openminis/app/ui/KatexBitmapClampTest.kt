package com.openminis.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KatexBitmapClampTest {

    private val maxEdge = 2048
    private val maxPixels = 4_000_000

    private fun clamp(rawW: Int, rawH: Int): Pair<Int, Int> {
        val edgeScale = minOf(
            1f,
            maxEdge.toFloat() / rawW,
            maxEdge.toFloat() / rawH,
        )
        val pixelScale = if (rawW.toLong() * rawH > maxPixels) {
            kotlin.math.sqrt(maxPixels.toDouble() / (rawW.toDouble() * rawH)).toFloat()
        } else {
            1f
        }
        val scale = minOf(edgeScale, pixelScale)
        return Pair(
            (rawW * scale).toInt().coerceAtLeast(1),
            (rawH * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun bytes(wh: Pair<Int, Int>) = wh.first.toLong() * wh.second * 4

    @Test
    fun `ordinary inline formula is untouched`() {
        val raw = 550 to 165
        assertEquals(raw, clamp(raw.first, raw.second))
    }

    @Test
    fun `the reported wide display formula is clamped, not clipped`() {
        val (w, h) = clamp(8000, 165)
        assertTrue("width must respect the edge cap, got $w", w <= maxEdge)
        val ratioBefore = 8000.0 / 165.0
        val ratioAfter = w.toDouble() / h
        assertTrue(
            "aspect ratio must be preserved (before=$ratioBefore after=$ratioAfter)",
            kotlin.math.abs(ratioBefore - ratioAfter) / ratioBefore < 0.02,
        )
        assertTrue("must actually shrink: was ${8000L * 165 * 4}B, now ${bytes(w to h)}B",
            bytes(w to h) < 8000L * 165 * 4)
    }

    @Test
    fun `a large square formula is bounded by total pixels, not just edges`() {
        val raw = 2047 to 2047
        assertTrue("edge cap alone would not fire here", raw.first <= maxEdge && raw.second <= maxEdge)
        val clamped = clamp(raw.first, raw.second)
        assertTrue(
            "total pixels must respect the cap, got ${clamped.first.toLong() * clamped.second}",
            clamped.first.toLong() * clamped.second <= maxPixels,
        )
    }

    @Test
    fun `no clamped bitmap can exceed the byte ceiling`() {
        val pathological = listOf(
            8192 to 8192, 8192 to 100, 100 to 8192, 3000 to 3000, 5000 to 1200,
        )
        for ((w, h) in pathological) {
            val clamped = clamp(w, h)
            assertTrue("edge exceeded for ${w}x$h -> $clamped", clamped.first <= maxEdge && clamped.second <= maxEdge)
            assertTrue(
                "byte ceiling exceeded for ${w}x$h -> $clamped = ${bytes(clamped)}B",
                bytes(clamped) <= maxPixels.toLong() * 4,
            )
        }
    }

    @Test
    fun `clamping never produces a zero dimension`() {
        val (w, h) = clamp(8192, 1)
        assertTrue("width must stay positive, got $w", w >= 1)
        assertTrue("height must stay positive, got $h", h >= 1)
    }
}
