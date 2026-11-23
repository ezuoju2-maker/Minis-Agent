package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeEdgeCarryoverTest {
    private val md by lazy {
        File("src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt").readText()
    }
    private val body by lazy {
        md.substringAfter("private fun MarkdownBlockBody(").substringBefore("\n@Composable")
    }

    @Test
    fun `the carry is declared ABOVE the branch switch`() {
        val decl = body.indexOf("val liveCarry = remember { arrayOf<Pair<String, List<MdBlock>>?>(null) }")
        val branch = body.indexOf("if (!isStreaming) {")
        assertTrue("holder must exist", decl >= 0)
        assertTrue("holder must precede the live/frozen branch so it survives the flip", decl < branch)
    }

    @Test
    fun `the frozen branch falls back to an exact-match carry before parsing`() {
        val start = body.indexOf("val cached = remember(rawText) {")
        val hit = body.indexOf("MarkdownParseCaches.cachedBlocks(rawText)", start)
        val carry = body.indexOf("?: liveCarry[0]?.takeIf { it.first == rawText }?.second", start)
        assertTrue("cache lookup must be inside the remember", hit > start && hit - start < 200)
        assertTrue(
            "an in-flight deposit must not force a re-parse",
            carry > hit && carry - start < 600,
        )
    }

    @Test
    fun `a prefix-match carry is painted instead of the plain-text preview`() {
        assertTrue(
            "same-flush case: the 4-row parse must render while the 5-row parse runs",
            body.contains("val blocks = parsed ?: liveCarry[0]?.takeIf { rawText.startsWith(it.first) }?.second"),
        )
    }

    @Test
    fun `the live branch records every parse it renders`() {
        val live = body.substringAfter("blocks = computed")
        assertTrue(live.contains("liveCarry[0] = displayContent to computed"))
    }

    @Test
    fun `the carry is a plain holder, never snapshot state`() {
        assertTrue(!body.contains("mutableStateOf<Pair<String, List<MdBlock>>?>"))
    }
}
