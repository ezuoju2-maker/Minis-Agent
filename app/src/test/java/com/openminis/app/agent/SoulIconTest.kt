package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulIconTest {


    @Test
    fun `bare ascii digits are not emoji`() {
        for (d in '0'..'9') {
            assertFalse("digit $d must not count as emoji", SoulIcon.isEmojiGlyph(d.toString()))
        }
        assertFalse(SoulIcon.isEmojiGlyph("#"))
        assertFalse(SoulIcon.isEmojiGlyph("*"))
    }

    @Test
    fun `letters cjk and punctuation are not emoji`() {
        assertFalse(SoulIcon.isEmojiGlyph("a"))
        assertFalse(SoulIcon.isEmojiGlyph("Z"))
        assertFalse(SoulIcon.isEmojiGlyph("中"))
        assertFalse(SoulIcon.isEmojiGlyph("。"))
        assertFalse(SoulIcon.isEmojiGlyph("!"))
        assertFalse(SoulIcon.isEmojiGlyph(" "))
    }


    @Test
    fun `keycap flag zwj and skin tone each count as one emoji`() {
        assertTrue("keycap 1", SoulIcon.isEmojiGlyph("1️⃣"))
        assertTrue("flag JP", SoulIcon.isEmojiGlyph("🇯🇵"))
        assertTrue("zwj woman technologist", SoulIcon.isEmojiGlyph("👩‍💻"))
        assertTrue("skin toned thumbs up", SoulIcon.isEmojiGlyph("👍🏽"))
    }

    @Test
    fun `grapheme clustering keeps composite emoji intact`() {
        assertEquals(1, SoulIcon.graphemeClusters("🇯🇵").size)
        assertEquals(1, SoulIcon.graphemeClusters("👩‍💻").size)
        assertEquals(1, SoulIcon.graphemeClusters("1️⃣").size)
    }


    @Test
    fun `single emoji passes through`() {
        assertEquals("⚡", SoulIcon.normalizeEmojiInput("⚡"))
        assertEquals("✨", SoulIcon.normalizeEmojiInput("✨"))
    }

    @Test
    fun `second emoji replaces the first`() {
        assertEquals("🤖", SoulIcon.normalizeEmojiInput("⚡🤖"))
        assertEquals("🐳", SoulIcon.normalizeEmojiInput("✨🤖🐳"))
    }

    @Test
    fun `non emoji input is silently dropped`() {
        assertEquals("", SoulIcon.normalizeEmojiInput("abc123"))
        assertEquals("", SoulIcon.normalizeEmojiInput("hello"))
        assertEquals("", SoulIcon.normalizeEmojiInput("中文"))
        assertEquals("", SoulIcon.normalizeEmojiInput(""))
    }

    @Test
    fun `emoji is extracted from surrounding text`() {
        assertEquals("⚡", SoulIcon.normalizeEmojiInput("abc⚡123"))
    }

    @Test
    fun `all suggested emoji are accepted and are single clusters`() {
        assertEquals(16, SoulIcon.SUGGESTED_EMOJI.size)
        for (e in SoulIcon.SUGGESTED_EMOJI) {
            assertTrue("suggestion $e must be one grapheme cluster",
                SoulIcon.graphemeClusters(e).size == 1)
            assertTrue("suggestion $e must pass the emoji test", SoulIcon.isEmojiGlyph(e))
            assertEquals("suggestion $e must survive normalization",
                e, SoulIcon.normalizeEmojiInput(e))
        }
    }


    @Test
    fun `there is no opaque rejection`() {
        val names = SoulIcon.Rejection.entries.map { it.name }
        assertFalse("opaque images must be accepted", names.contains("OPAQUE"))
        assertTrue(names.contains("UNREADABLE"))
        assertTrue(names.contains("TOO_LARGE"))
    }

    @Test
    fun `corner radius matches the ios app-icon proportion`() {
        assertEquals(0.22f, SoulIcon.CORNER_RADIUS_FRACTION, 0.0001f)
    }

    @Test
    fun `stored size cap is 64k`() {
        assertEquals(64 * 1024, SoulIcon.MAX_DATA_URI_CHARS)
    }


    @Test
    fun `data uri is recognized and emoji is not`() {
        assertTrue(SoulIcon.isDataUri("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(SoulIcon.isDataUri("⚡"))
        assertFalse(SoulIcon.isDataUri(""))
        assertFalse(SoulIcon.isDataUri("data:image/jpeg;base64,xxx"))
    }


    @Test
    fun `icon line is omitted entirely when empty`() {
        val f = SoulFile(SoulMetadata.DEFAULT, "body")
        val out = SoulMDParser.serialize(f)
        assertFalse("empty icon must not write a line", out.contains("icon:"))
        assertTrue(out.contains("name:"))
        assertTrue(out.contains("style:"))
        assertTrue(out.contains("lang:"))
    }

    @Test
    fun `emoji icon round trips through serialize and parse`() {
        val f = SoulFile(SoulMetadata.DEFAULT.copy(icon = "⚡"), "body")
        val text = SoulMDParser.serialize(f)
        assertTrue(text.contains("icon: \"⚡\""))
        assertEquals("⚡", SoulMDParser.parse(text).metadata.icon)
    }

    @Test
    fun `data uri icon survives the colon-bearing round trip`() {
        val uri = SoulIcon.DATA_URI_PREFIX + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"
        val f = SoulFile(SoulMetadata.DEFAULT.copy(icon = uri), "body")
        val text = SoulMDParser.serialize(f)
        val back = SoulMDParser.parse(text).metadata.icon
        assertEquals(uri, back)
        assertTrue(SoulIcon.isDataUri(back))
    }

    @Test
    fun `clearing the icon removes the line from an existing file`() {
        val withIcon = SoulMDParser.serialize(
            SoulFile(SoulMetadata.DEFAULT.copy(icon = "🚀"), "body"),
        )
        assertTrue(withIcon.contains("icon:"))

        val parsed = SoulMDParser.parse(withIcon)
        val cleared = SoulMDParser.serialize(
            parsed.copy(metadata = parsed.metadata.copy(icon = "")),
        )
        assertFalse("clearing must delete the line, not write icon: \"\"",
            cleared.contains("icon:"))
        assertEquals("", SoulMDParser.parse(cleared).metadata.icon)
    }

    @Test
    fun `a file predating the icon key still parses`() {
        val old = """
            ---
            name: "Minis"
            style: ""
            lang: "auto"
            ---

            body text
        """.trimIndent()
        val parsed = SoulMDParser.parse(old)
        assertEquals("", parsed.metadata.icon)
        assertEquals("Minis", parsed.metadata.name)
    }

    @Test
    fun `unknown frontmatter keys remain non-fatal`() {
        val text = """
            ---
            name: "Minis"
            icon: "⚡"
            somethingNew: "value"
            lang: "auto"
            ---

            body
        """.trimIndent()
        val parsed = SoulMDParser.parse(text)
        assertEquals("⚡", parsed.metadata.icon)
        assertEquals("Minis", parsed.metadata.name)
    }
}
