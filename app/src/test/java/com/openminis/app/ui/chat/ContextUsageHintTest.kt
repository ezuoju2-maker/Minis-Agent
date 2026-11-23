package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUsageHintTest {


    @Test
    fun `below 70 percent is normal and shows nothing`() {
        val usage = ContextUsage(usedTokens = 69_000, windowTokens = 100_000)
        assertEquals(ContextUsage.Tier.NORMAL, usage.tier)
        assertEquals(69, usage.percent)
    }

    @Test
    fun `exactly 70 percent is warning`() {
        val usage = ContextUsage(usedTokens = 70_000, windowTokens = 100_000)
        assertEquals(ContextUsage.Tier.WARNING, usage.tier)
    }

    @Test
    fun `exactly 80 percent is critical`() {
        val usage = ContextUsage(usedTokens = 80_000, windowTokens = 100_000)
        assertEquals(ContextUsage.Tier.CRITICAL, usage.tier)
    }

    @Test
    fun `percent rounds rather than truncates`() {
        val usage = ContextUsage(usedTokens = 69_600, windowTokens = 100_000)
        assertEquals(70, usage.percent)
        assertEquals(ContextUsage.Tier.NORMAL, usage.tier)
    }


    @Test
    fun `no usage data yields no hint rather than zero percent`() {
        assertNull(ContextUsage.from(usedTokens = 0, windowTokens = 200_000))
        assertNull(ContextUsage.from(usedTokens = 124_000, windowTokens = null))
        assertNull(ContextUsage.from(usedTokens = 124_000, windowTokens = 0))
    }

    @Test
    fun `valid numbers produce a usage`() {
        val usage = ContextUsage.from(usedTokens = 124_000, windowTokens = 200_000)
        assertEquals(ContextUsage(124_000, 200_000), usage)
        assertEquals(62, usage!!.percent)
    }


    @Test
    fun `token counts format like the usage capsule`() {
        assertEquals("850", TokenCountFormatter.short(850))
        assertEquals("1.2k", TokenCountFormatter.short(1_200))
        assertEquals("124k", TokenCountFormatter.short(124_000))
        assertEquals("200k", TokenCountFormatter.short(200_000))
        assertEquals(
            "124k / 200k",
            TokenCountFormatter.sizePair(ContextUsage(124_000, 200_000)),
        )
    }


    @Test
    fun `upward crossing fires and same tier does not`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 1_000))
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 1_000)
        assertFalse(t.observe(ContextUsage.Tier.WARNING, nowMs = 2_000))
        assertFalse(t.observe(ContextUsage.Tier.WARNING, nowMs = 60_000))
    }

    @Test
    fun `downward movement never fires but does re-arm`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.CRITICAL, nowMs = 0))
        t.markFired(ContextUsage.Tier.CRITICAL, nowMs = 0)
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 10_000))
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 20_000))
    }

    @Test
    fun `flapping across one line is rate limited`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 0))
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 0)
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 500))
        assertFalse(t.observe(ContextUsage.Tier.WARNING, nowMs = 1_000))
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 5_000))
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 5_100))
    }

    @Test
    fun `a rise to a higher tier is never rate limited`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 0))
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 0)
        assertTrue(t.observe(ContextUsage.Tier.CRITICAL, nowMs = 1_000))
    }

    @Test
    fun `suppressed crossing does not start the cooldown`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 0))
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 100))
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 200))
    }

    @Test
    fun `reset adopts the loaded tier without firing`() {
        val t = ContextTierCrossingTracker()
        t.reset(ContextUsage.Tier.CRITICAL)
        assertEquals(ContextUsage.Tier.CRITICAL, t.lastTier)
        assertFalse(t.observe(ContextUsage.Tier.CRITICAL, nowMs = 1_000))
    }

    @Test
    fun `recentlyFired lets loop end skip a line mid loop just showed`() {
        val t = ContextTierCrossingTracker()
        t.observe(ContextUsage.Tier.WARNING, nowMs = 0)
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 0)
        assertTrue(t.recentlyFired(ContextUsage.Tier.WARNING, nowMs = 1_000))
        assertFalse(t.recentlyFired(ContextUsage.Tier.CRITICAL, nowMs = 1_000))
        assertFalse(t.recentlyFired(ContextUsage.Tier.WARNING, nowMs = 9_000))
    }


    private fun hint(generation: Int, tier: ContextUsage.Tier = ContextUsage.Tier.WARNING) =
        ContextUsageHint(
            generation = generation,
            text = "Context 70% used · 124k / 200k",
            highlights = listOf("70%", "124k / 200k"),
            tier = tier,
        )

    @Test
    fun `first focus is protected and second focus retires the line`() {
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.present(hint(1)))
        assertTrue(lifecycle.isShowing)

        assertFalse(lifecycle.onFocusGained())
        assertTrue(lifecycle.isShowing)

        assertTrue(lifecycle.onFocusGained())
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `focus rotates normally when no hint is showing`() {
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.onFocusGained())
        assertTrue(lifecycle.onFocusGained())
    }

    @Test
    fun `typing retires the line`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        lifecycle.onTextChanged("h")
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `empty text does not retire and clearing does not revive`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        lifecycle.onTextChanged("")
        assertTrue(lifecycle.isShowing)

        lifecycle.onTextChanged("draft")
        assertFalse(lifecycle.isShowing)
        lifecycle.onTextChanged("")
        assertFalse(lifecycle.isShowing)
        assertFalse(lifecycle.present(hint(1)))
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `a new generation shows a line again after one was retired`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        lifecycle.onTextChanged("typed")
        assertFalse(lifecycle.isShowing)

        assertTrue(lifecycle.present(hint(2)))
        assertTrue(lifecycle.isShowing)
        assertFalse(lifecycle.hasProtectedFirstFocus)
        assertFalse(lifecycle.onFocusGained())
        assertTrue(lifecycle.isShowing)
    }

    @Test
    fun `re-presenting the same generation is idempotent`() {
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.present(hint(7)))
        assertFalse(lifecycle.onFocusGained())
        assertTrue(lifecycle.hasProtectedFirstFocus)

        assertFalse(lifecycle.present(hint(7)))
        assertTrue(lifecycle.hasProtectedFirstFocus)
        assertTrue(lifecycle.onFocusGained())
        assertFalse(lifecycle.isShowing)
    }


    @Test
    fun `keyboard hidden then shown again retires the line`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))

        assertFalse(lifecycle.onImeShown())
        assertTrue(lifecycle.isShowing)

        assertTrue(lifecycle.isShowing)

        assertTrue(lifecycle.onImeShown())
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `ime and focus triggers share one protection budget`() {
        val viaImeFirst = ContextUsageHintLifecycle()
        viaImeFirst.present(hint(1))
        assertFalse(viaImeFirst.onImeShown())
        assertTrue(viaImeFirst.onFocusGained())
        assertFalse(viaImeFirst.isShowing)

        val viaFocusFirst = ContextUsageHintLifecycle()
        viaFocusFirst.present(hint(1))
        assertFalse(viaFocusFirst.onFocusGained())
        assertTrue(viaFocusFirst.onImeShown())
        assertFalse(viaFocusFirst.isShowing)
    }

    @Test
    fun `ime shown with no hint just rotates`() {
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.onImeShown())
        assertTrue(lifecycle.onImeShown())
    }

    @Test
    fun `a new generation re-arms protection for the ime path`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        assertFalse(lifecycle.onImeShown())
        assertTrue(lifecycle.onImeShown())
        assertFalse(lifecycle.isShowing)

        assertTrue(lifecycle.present(hint(2)))
        assertFalse(lifecycle.onImeShown())
        assertTrue(lifecycle.isShowing)
    }


    @Test
    fun `glow tier depends only on the measurement crossing a threshold`() {
        val w = 32_000
        assertEquals(ContextUsage.Tier.NORMAL, ContextUsage.from(22_000, w)!!.tier)
        assertEquals(ContextUsage.Tier.WARNING, ContextUsage.from(22_400, w)!!.tier)
        assertEquals(ContextUsage.Tier.WARNING, ContextUsage.from(25_000, w)!!.tier)
        assertEquals(ContextUsage.Tier.CRITICAL, ContextUsage.from(25_600, w)!!.tier)
        assertEquals(ContextUsage.Tier.CRITICAL, ContextUsage.from(30_000, w)!!.tier)
    }

    @Test
    fun `sending a message does not by itself change the glow`() {
        val before = ContextUsage.from(25_000, 32_000)
        val afterSend = ContextUsage.from(25_000, 32_000)
        assertEquals(before, afterSend)
        assertEquals(before!!.tier, afterSend!!.tier)
    }

    @Test
    fun `a dropped measurement is not a below-threshold reading`() {
        assertNull(ContextUsage.from(0, 32_000))
    }

    @Test
    fun `changing the window alone moves the tier`() {
        assertEquals(ContextUsage.Tier.NORMAL, ContextUsage.from(25_000, 128_000)!!.tier)
        assertEquals(ContextUsage.Tier.WARNING, ContextUsage.from(25_000, 32_000)!!.tier)
        assertEquals(ContextUsage.Tier.CRITICAL, ContextUsage.from(27_000, 32_000)!!.tier)
    }
}
