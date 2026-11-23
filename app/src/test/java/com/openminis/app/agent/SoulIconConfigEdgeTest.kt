package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulIconConfigEdgeTest {

    private enum class Branch { CLEAR, EMOJI, IMAGE_SOURCE }

    private fun branch(raw: String): Branch {
        val t = raw.trim()
        return when {
            t.isEmpty() -> Branch.CLEAR
            SoulIcon.graphemeClusters(t).size == 1 && SoulIcon.isEmojiGlyph(t) -> Branch.EMOJI
            else -> Branch.IMAGE_SOURCE
        }
    }

    private fun refusedAsImage(raw: String): Boolean {
        if (branch(raw) != Branch.IMAGE_SOURCE) return false
        return SoulIcon.classifySource(raw.trim()) is SoulIcon.Source.Unsupported
    }


    @Test
    fun `multiple emoji are refused rather than silently truncated`() {
        for (v in listOf("⚡🤖", "✨🚀🐳", "🐱🦊")) {
            assertEquals("'$v' must not be taken as a single emoji", Branch.IMAGE_SOURCE, branch(v))
            assertTrue("'$v' must be refused outright", refusedAsImage(v))
        }
    }

    @Test
    fun `the ui normalizer keeps the last emoji while config refuses the same input`() {
        assertEquals("🤖", SoulIcon.normalizeEmojiInput("⚡🤖"))
        assertTrue(refusedAsImage("⚡🤖"))
    }


    @Test
    fun `plain text is refused`() {
        for (v in listOf("hello", "abc123", "Minis", "n/a", "null", "undefined")) {
            assertEquals("'$v'", Branch.IMAGE_SOURCE, branch(v))
            assertTrue("'$v' must be refused", refusedAsImage(v))
        }
    }

    @Test
    fun `cjk and punctuation are refused`() {
        for (v in listOf("中", "中文", "。", "！", "——")) {
            assertTrue("'$v' must be refused", refusedAsImage(v))
        }
    }

    @Test
    fun `bare digits and keycap bases are refused`() {
        for (v in listOf("1", "0", "9", "#", "*")) {
            assertEquals("'$v' must not classify as emoji", Branch.IMAGE_SOURCE, branch(v))
            assertTrue("'$v' must be refused", refusedAsImage(v))
        }
    }


    @Test
    fun `empty and whitespace clear to default`() {
        assertEquals(Branch.CLEAR, branch(""))
        assertEquals(Branch.CLEAR, branch("   "))
        assertEquals(Branch.CLEAR, branch("\t"))
        assertEquals(Branch.CLEAR, branch("\n"))
    }

    @Test
    fun `a padded emoji still applies`() {
        assertEquals(Branch.EMOJI, branch("  ⚡  "))
    }


    @Test
    fun `flag keycap zwj and skin tone are accepted as single emoji`() {
        for (v in listOf("🇯🇵", "1️⃣", "👩‍💻", "👍🏽")) {
            assertEquals("'$v' must be one emoji", Branch.EMOJI, branch(v))
        }
    }


    @Test
    fun `garbage image sources are refused with a reason`() {
        val cases = listOf(
            "data:image/png;base64,!!!not-base64!!!",
            "data:text/plain,hello",
            "minis://",
            "/etc/passwd",
            "/data/data/com.openminis.app/databases/chat.db",
            "http://example.com/x.png",
            "https://example.com/x.png",
        )
        for (v in cases) {
            val s = SoulIcon.classifySource(v)
            val refused = s is SoulIcon.Source.Unsupported ||
                s is SoulIcon.Source.LinuxPath
            assertTrue("'$v' must not decode to bytes", refused)
            assertFalse("'$v' must not be Bytes", s is SoulIcon.Source.Bytes)
        }
    }

    @Test
    fun `paths outside the minis roots are not allowed`() {
        val outside = listOf(
            "/etc/passwd",
            "/data/data/com.openminis.app/databases/chat.db",
            "/var/minis/../../etc/passwd",
            "/var/miniswhatever/x.png",
        )
        for (p in outside) {
            val allowed = SoulIcon.ALLOWED_LINUX_ROOTS.any { p == it || p.startsWith("$it/") }
            assertFalse("'$p' must not match an allowed root", allowed)
        }
    }


    @Test
    fun `stored values round trip without breaking frontmatter`() {
        val storable = listOf("⚡", "🇯🇵", "👩‍💻", SoulIcon.DATA_URI_PREFIX + "iVBORw0KGgo=")
        for (v in storable) {
            assertFalse("'$v' must be single-line", v.contains('\n'))
            val text = SoulMDParser.serialize(
                SoulFile(SoulMetadata.DEFAULT.copy(icon = v), "body"),
            )
            val back = SoulMDParser.parse(text)
            assertEquals(v, back.metadata.icon)
            assertEquals("Minis", back.metadata.name)
            assertEquals("auto", back.metadata.lang)
            assertEquals("body", back.body.trim())
        }
    }

    @Test
    fun `a rejected value never reaches a stored form`() {
        for (v in listOf("⚡🤖", "hello", "1", "中文")) {
            val s = SoulIcon.classifySource(v)
            assertTrue(
                "'$v' must classify as Unsupported so the writer throws before saving",
                s is SoulIcon.Source.Unsupported,
            )
        }
    }
}
