package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KaTeXCacheKeyThemeTest {

    @Test
    fun `light and dark keys differ for the same formula`() {
        val light = KaTeXRendererCache.cacheKey("E = mc^2", displayMode = true, isDark = false)
        val dark = KaTeXRendererCache.cacheKey("E = mc^2", displayMode = true, isDark = true)
        assertNotEquals(
            "a theme flip must MISS the cache — same key means the previous " +
                "theme's baked-in bitmap is served (light=$light dark=$dark)",
            light,
            dark,
        )
    }

    @Test
    fun `key still separates display from inline`() {
        assertNotEquals(
            "display and inline render differently and must not share an entry",
            KaTeXRendererCache.cacheKey("x^2", displayMode = true, isDark = true),
            KaTeXRendererCache.cacheKey("x^2", displayMode = false, isDark = true),
        )
    }

    @Test
    fun `key is stable for identical inputs`() {
        assertEquals(
            "same inputs must hit the same entry, or the cache never pays off",
            KaTeXRendererCache.cacheKey("\\sum_{i=1}^{n} i", displayMode = true, isDark = false),
            KaTeXRendererCache.cacheKey("\\sum_{i=1}^{n} i", displayMode = true, isDark = false),
        )
    }

    @Test
    fun `theme marker cannot collide with formula text`() {
        assertNotEquals(
            KaTeXRendererCache.cacheKey("k:x", displayMode = true, isDark = false),
            KaTeXRendererCache.cacheKey("x", displayMode = true, isDark = true),
        )
    }
}
