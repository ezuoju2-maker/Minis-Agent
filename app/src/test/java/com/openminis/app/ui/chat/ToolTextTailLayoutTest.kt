package com.openminis.app.ui.chat

import android.text.StaticLayout
import android.text.TextPaint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolTextTailLayoutTest {

    private fun layoutMs(text: String): Double {
        val paint = TextPaint().apply { textSize = 14f }
        val t0 = System.nanoTime()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 300)
            .setMaxLines(12)
            .build()
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("lineCount=${layout.lineCount} width=${layout.getLineWidth(0)}", layout.lineCount >= 1)
        return ms
    }

    @Test fun `capped tail lays out fast whatever the raw line length`() {
        layoutMs("warm up " + "w".repeat(2_000))
        for (size in listOf(50_000, 200_000, 1_000_000)) {
            val raw = "x".repeat(size)
            val capped = ToolTextTail.lastLines(raw, 12)
            val cappedMs = layoutMs(capped)
            val rawMs = if (size <= 200_000) layoutMs(raw) else Double.NaN
            println("[ToolTextTailLayout] size=$size raw=${"%.1f".format(rawMs)}ms capped(${capped.length})=${"%.1f".format(cappedMs)}ms")
            assertTrue("capped layout took $cappedMs ms for size=$size", cappedMs < 250.0)
        }
    }
}
