package com.openminis.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCapsuleWidthTest {

    private val fraction = 0.50f
    private val capFraction = 0.70f
    private val capDp = 240
    private val floorDp = 180

    private fun capsuleWidthDp(screenDp: Int): Int {
        val cap = minOf(screenDp * capFraction, capDp.toFloat())
        return maxOf(minOf(screenDp * fraction, cap), floorDp.toFloat()).toInt()
    }

    private fun textBudgetDp(screenDp: Int) = maxOf(capsuleWidthDp(screenDp) - 76, 80)


    @Test
    fun `landscape phone no longer stretches the capsule`() {
        val w = capsuleWidthDp(851)
        assertEquals(240, w)
        assertTrue("capsule must be well under a third of a landscape screen", w < 851 / 3)
    }

    @Test
    fun `tablet landscape stays compact`() {
        val w = capsuleWidthDp(1280)
        assertEquals(240, w)
        assertTrue("must be under a quarter of a tablet landscape screen", w < 1280 / 4)
    }

    @Test
    fun `tablet portrait is capped too, not just landscape`() {
        assertEquals(240, capsuleWidthDp(800))
    }


    @Test
    fun `portrait phones are untouched by the tighter ceiling`() {
        assertEquals(196, capsuleWidthDp(393))
        assertEquals(205, capsuleWidthDp(411))
        assertTrue("phone portrait must not hit the ceiling", capsuleWidthDp(411) < capDp)
    }

    @Test
    fun `tiny screens keep their floor`() {
        assertEquals(floorDp, capsuleWidthDp(320))
    }

    @Test
    fun `the narrow-screen fraction still guards the ceiling`() {
        val cap = minOf(300 * capFraction, capDp.toFloat())
        assertEquals(210f, cap)
        assertTrue("the 0.70 fraction must bind below the dp ceiling", cap < capDp)
    }

    @Test
    fun `wide screens never get a smaller text budget than a portrait phone`() {
        val phone = textBudgetDp(393)
        for (wide in listOf(851, 914, 800, 1280, 1350)) {
            assertTrue(
                "wide screen $wide dp budget ${textBudgetDp(wide)} must be >= phone $phone",
                textBudgetDp(wide) >= phone,
            )
        }
    }


    @Test
    fun `source constants match the values these tests pin`() {
        val src = File("src/main/java/com/openminis/app/service/ToolOverlayController.kt")
        assertTrue("missing ToolOverlayController source", src.exists())
        val text = src.readText()
        assertTrue(
            "CAPSULE_WIDTH_CAP_DP must be $capDp — a looser ceiling is the bug",
            text.contains("CAPSULE_WIDTH_CAP_DP = $capDp"),
        )
        assertTrue(text.contains("CAPSULE_WIDTH_CAP_FRACTION = 0.70f"))
        assertTrue(text.contains("CAPSULE_WIDTH_FRACTION = 0.50f"))
        assertTrue(text.contains("CAPSULE_WIDTH_FLOOR_DP = $floorDp"))
    }

    @Test
    fun `the capsule width is applied to both the window and the text rows`() {
        val src = File("src/main/java/com/openminis/app/service/ToolOverlayController.kt").readText()
        assertTrue(
            "text rows must derive their budget from fixedCapsuleWidthPx()",
            src.contains("val maxW = (fixedCapsuleWidthPx()"),
        )
        assertTrue(
            "rotation must recompute the width rather than keep the stale one",
            src.substringAfter("fun onConfigurationChanged()").contains("fixedCapsuleWidthPx()"),
        )
    }
}
