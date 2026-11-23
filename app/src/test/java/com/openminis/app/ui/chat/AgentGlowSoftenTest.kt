package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGlowSoftenTest {

    private val src by lazy { ProductionSources.read("ui/chat/ChatComposerWidgets.kt") }

    private fun alphaAt(depthDp: Double, band: Double = 32.0, peak: Double = 0.11): Double {
        if (depthDp >= band) return 0.0
        val fade = 1.0 - depthDp / band
        return peak * fade * fade * fade
    }

    private fun oldAlphaAt(depthDp: Double): Double {
        if (depthDp >= 10.0) return 0.0
        val fade = 1.0 - depthDp / 10.0
        return 0.55 * fade * fade
    }

    @Test
    fun `the glow is paler at the edge than it was`() {
        assertTrue("edge alpha must drop well below the old 0.55", alphaAt(0.0) < 0.25)
        assertEquals(0.11, alphaAt(0.0), 1e-9)
        assertTrue(alphaAt(0.0) < oldAlphaAt(0.0) / 2)
    }

    @Test
    fun `the glow reaches much further in`() {
        assertEquals(0.0, oldAlphaAt(12.0), 1e-9)
        assertTrue("must still carry colour at 14dp", alphaAt(14.0) > 0.0)
        assertTrue("still tinting at 22dp since the 32dp widening", alphaAt(22.0) > 0.0)
        assertEquals(0.0, alphaAt(32.0), 1e-9)
    }

    @Test
    fun `past the old band the new glow is the stronger of the two`() {
        for (d in listOf(9.0, 10.0, 12.0, 15.0)) {
            assertTrue("at ${d}dp the new glow should still be there", alphaAt(d) > oldAlphaAt(d))
        }
    }

    @Test
    fun `total colour laid down is less than before`() {
        fun integral(f: (Double) -> Double, to: Double): Double {
            var s = 0.0
            var x = 0.0
            while (x < to) { s += f(x) * 0.01; x += 0.01 }
            return s
        }
        val old = integral(::oldAlphaAt, 10.0)
        val new = integral({ alphaAt(it) }, 32.0)
        assertTrue("new=$new must be below old=$old", new < old)
    }

    @Test
    fun `the band never collapses the rect on the tile it draws on`() {
        val band = 32.0
        assertTrue("band must stay clear of the 32.5dp half-height", band < 65.0 / 2)
        assertTrue("production keeps the degenerate-rect guard", src.contains("if (w <= 0f || h <= 0f) break"))
    }

    @Test
    fun `strokes overlap so the corners do not band`() {
        val step = 0.5
        val diagonalSpacing = step * Math.sqrt(2.0)
        assertTrue("a 1x-width stroke would gap on the diagonal", step < diagonalSpacing)
        assertTrue("a 2x-width stroke covers it", step * 2 > diagonalSpacing)
        assertTrue("production draws at 2x the spacing", src.contains("style = Stroke(width = step * 2f)"))
    }

    @Test
    fun `the corner radius is inset by subtraction, not scaled`() {
        assertTrue(
            "radius must stay (radius - inset), floored at 0",
            src.contains("CornerRadius((radius - inset).coerceAtLeast(0f))"),
        )
    }

    @Test
    fun `the production constants are the ones tested here`() {
        val draw = src.substringAfter("val subAgentGlow = helperAccent()")
        assertTrue("band", draw.contains("val band = 32.dp.toPx()"))
        assertTrue("step", draw.contains("val step = 0.5.dp.toPx()"))
        assertTrue("peak alpha + cubic falloff", draw.contains("0.11f * fade * fade * fade"))
    }

    @Test
    fun `the glow is drawn inside the clip, after the content`() {
        val i = src.indexOf("val subAgentGlow = helperAccent()")
        val clip = src.lastIndexOf(".clip(thumbnailShape)", i.coerceAtLeast(0) + 4000)
        val draw = src.indexOf(".drawWithContent {", i)
        assertTrue("clip must precede the glow draw", clip in 1 until draw)
        val body = src.substring(draw, draw + 600)
        assertTrue("content is drawn first", body.indexOf("drawContent()") < body.indexOf("subAgentLive"))
    }
}
