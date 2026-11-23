package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFadeSeedTest {

    private fun activeRanges(c: FadeController): List<IntRange> {
        val out = mutableListOf<IntRange>()
        c.forEachActive { s, e, _ -> out += s until e }
        return out
    }


    @Test
    fun `a fresh controller fed via ingest fades ALL of its first text`() {
        val c = FadeController()
        c.ingest("hello world")
        assertTrue(c.hasSeenText)
        assertEquals(listOf(0 until 5, 6 until 11), activeRanges(c))
    }

    @Test
    fun `seed adopts text as already visible - no ranges, no mask`() {
        val c = FadeController()
        c.seed("華南的廣州、深圳：高溫又可能有雷雨")
        assertTrue(c.hasSeenText)
        assertFalse("nothing may be fading", c.hasActiveRanges)
        assertEquals("prefix recorded", "華南的廣州、深圳：高溫又可能有雷雨", c.lastPlainText)
    }

    @Test
    fun `the reported shape - a controller born after render never masks offset 0`() {
        val visible = "華南的廣州、深圳：高溫又可能有雷雨 ⚡"
        val c = FadeController()
        c.seed(visible)
        c.ingest(visible)
        assertFalse("already-visible text must not be re-faded", c.hasActiveRanges)
        assertTrue(activeRanges(c).none { 0 in it })
    }

    @Test
    fun `words appended AFTER a seed still fade in`() {
        val c = FadeController()
        c.seed("hello world")
        c.ingest("hello world foo bar")
        val r = activeRanges(c)
        assertEquals(2, r.size)
        assertEquals("first new word starts after the seeded prefix", 12, r[0].first)
        assertTrue("no range reaches back into seeded text", r.all { it.first >= 12 })
    }

    @Test
    fun `seed then diverged text clears rather than fades`() {
        val c = FadeController()
        c.seed("abc def")
        c.ingest("xyz")
        assertFalse(c.hasActiveRanges)
        assertEquals("xyz", c.lastPlainText)
    }

    @Test
    fun `hasSeenText is set by both entry points`() {
        assertTrue(FadeController().apply { seed("a") }.hasSeenText)
        assertTrue(FadeController().apply { ingest("a") }.hasSeenText)
        assertFalse(FadeController().hasSeenText)
    }


    private val md by lazy {
        File("src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt").readText()
    }

    @Test
    fun `MdText routes a late-born controller to seed, a birth controller to ingest`() {
        val fade = md.substringAfter("val fadeEnabled = LocalAppendOnlyFade.current")
            .substringBefore("FadeFrameDriver(fadeController)")
        assertTrue(
            "must capture fade state at birth with a KEYLESS remember",
            fade.contains("val fadeFromBirth = remember { fadeEnabled }"),
        )
        assertTrue(
            "late-born controller must seed",
            fade.contains("if (!fadeController.hasSeenText)") &&
                fade.contains("fadeBirthPlan(fadeFromBirth,") &&
                fade.contains("FadeBirth.SeedAll -> fadeController.seed(text.text)"),
        )
        assertEquals(FadeBirth.SeedAll, fadeBirthPlan(false, emptyList(), "shown before the flip"))
        assertTrue("birth controller must still ingest", fade.contains("fadeController.ingest(text.text)"))
    }

    @Test
    fun `no block loop wraps only the last block any more`() {
        assertEquals(
            "a literal `provides true` on the fade local means a last-block-only wrapper is back",
            0,
            Regex("LocalAppendOnlyFade provides true,").findAll(md).count(),
        )
        assertTrue(
            "StreamingMarkdownTextBody must provide the flag as a VALUE for every block",
            md.contains("LocalAppendOnlyFade provides (idx == lastIdx),"),
        )
        assertTrue(
            "MarkdownBlockBody must provide the flag as a VALUE for every block",
            md.contains("LocalAppendOnlyFade provides isLast,") &&
                md.contains("LocalLiveIncremental provides isLast,"),
        )
    }

    @Test
    fun `the frozen-history path composes without per-block providers`() {
        val loopA = md.substringAfter("[T-android-stream-fade-seed] Every block goes through the SAME")
            .substringBefore("blocks.forEach { RenderBlock(it) }")
        assertTrue("loop A must gate the provider loop on isStreaming", loopA.contains("if (isStreaming) {"))
    }

    @Test
    fun `the live deposit is ungated and replaces the previous tick's entry`() {
        val dep = md.substringAfter("[T-android-freeze-edge-flash] This used to be gated")
            .substringBefore("coroutineContext.ensureActive()")
        assertFalse(
            "the 2000-char gate must be gone — it guaranteed small fragments flash",
            dep.contains("displayContent.length > COLD_PARSE_OFFMAIN_THRESHOLD_CHARS"),
        )
        assertTrue("must retire the previous tick's key", dep.contains("MarkdownParseCaches.removeBlocks(prev)"))
        assertTrue("must deposit the current parse", dep.contains("MarkdownParseCaches.putBlocks(displayContent, it)"))
        assertTrue("must remember the new key", dep.contains("liveDepositKey[0] = displayContent"))
        assertTrue(
            "the holder must be plain storage, not snapshot state",
            md.contains("val liveDepositKey = remember { arrayOf<String?>(null) }"),
        )
    }

    @Test
    fun `removeBlocks takes the same lock as putBlocks`() {
        val fn = md.substringAfter("fun removeBlocks(raw: String)").substringBefore("}")
        assertTrue(fn.contains("synchronized(blocksLru)"))
        assertTrue(fn.contains("blocksLru.remove(raw)"))
    }
}
