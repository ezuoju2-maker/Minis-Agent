package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSlashDispatchTest {


    private data class Tap(
        val composer: String,
        val caret: Int?,
        val action: String?,
        val unrecognized: Boolean,
    )

    private fun tap(
        id: String,
        title: String,
        isSkill: Boolean = false,
        isMcp: Boolean = false,
        saved: String? = null,
        withFix: Boolean = true,
    ): Tap {
        val isComposerFill = if (withFix) isSkill || isMcp else isSkill
        if (isComposerFill) {
            val prefix = "/$title "
            return if (saved != null) {
                Tap(composer = prefix + saved, caret = prefix.length, action = null, unrecognized = false)
            } else {
                Tap(composer = prefix, caret = null, action = null, unrecognized = false)
            }
        }
        var unrecognized = false
        val action = when (id) {
            "compact" -> "compactAll"
            "memory" -> "toggleMemoryEnabled"
            "thinking" -> "toggleThinking"
            "clear" -> "clearChatConfirmRequested"
            else -> { unrecognized = true; null }
        }
        return if (saved != null) {
            Tap(composer = saved, caret = saved.length, action = action, unrecognized = unrecognized)
        } else {
            Tap(composer = "", caret = null, action = action, unrecognized = unrecognized)
        }
    }


    @Test
    fun `an MCP row is a composer-fill row, not an unrecognized command`() {
        val t = tap(id = "mcp:test-mcp", title = "test-mcp", isMcp = true)
        assertFalse("an MCP tap must never reach the unrecognized fallback", t.unrecognized)
        assertEquals("no built-in action may run for an MCP row", null, t.action)
    }

    @Test
    fun `the pre-fix guard reproduces the reported failure`() {
        val t = tap(id = "mcp:test-mcp", title = "test-mcp", isMcp = true, withFix = false)
        assertTrue("without the fix the tap is unrecognized", t.unrecognized)
        assertEquals("without the fix the composer is cleared", "", t.composer)
    }


    @Test
    fun `tapping an MCP row on an empty composer yields slash name and a trailing space`() {
        val t = tap(id = "mcp:test-mcp", title = "test-mcp", isMcp = true)
        assertEquals("/test-mcp ", t.composer)
        assertTrue(
            "the trailing space is load-bearing — the user types arguments right after",
            t.composer.endsWith(" "),
        )
    }


    @Test
    fun `tapping an MCP row over existing text prepends the prefix and places the caret`() {
        val t = tap(id = "mcp:test-mcp", title = "test-mcp", isMcp = true, saved = "hello")
        assertEquals("/test-mcp hello", t.composer)
        assertEquals("caret lands after the prefix, before the original text", 10, t.caret)
        assertEquals("sanity: the caret is exactly the prefix length", "/test-mcp ".length, t.caret)
    }


    @Test
    fun `an MCP tap never clears the composer`() {
        assertNotEquals("", tap("mcp:test-mcp", "test-mcp", isMcp = true).composer)
        assertNotEquals("", tap("mcp:test-mcp", "test-mcp", isMcp = true, saved = "hello").composer)
        assertTrue(
            "the user's original text must survive the tap",
            tap("mcp:test-mcp", "test-mcp", isMcp = true, saved = "hello").composer.contains("hello"),
        )
    }


    @Test
    fun `skill rows behave exactly as before`() {
        val empty = tap(id = "skill:abc", title = "review", isSkill = true)
        assertEquals("/review ", empty.composer)
        assertFalse(empty.unrecognized)

        val over = tap(id = "skill:abc", title = "review", isSkill = true, saved = "this PR")
        assertEquals("/review this PR", over.composer)
        assertEquals("/review ".length, over.caret)

        assertEquals(
            tap("skill:abc", "review", isSkill = true, withFix = false),
            tap("skill:abc", "review", isSkill = true, withFix = true),
        )
    }


    @Test
    fun `built-in commands still dispatch by id and are not composer-filled`() {
        val expected = mapOf(
            "compact" to "compactAll",
            "memory" to "toggleMemoryEnabled",
            "thinking" to "toggleThinking",
            "clear" to "clearChatConfirmRequested",
        )
        for ((id, action) in expected) {
            val t = tap(id = id, title = id.replaceFirstChar { it.uppercase() })
            assertEquals("$id must dispatch its action", action, t.action)
            assertFalse("$id must not be unrecognized", t.unrecognized)
            assertFalse("$id must not fill the composer", t.composer.startsWith("/"))
        }
    }

    @Test
    fun `a built-in tapped over existing text still restores the original`() {
        val t = tap(id = "compact", title = "Compact", saved = "my draft")
        assertEquals("my draft", t.composer)
        assertEquals("my draft".length, t.caret)
        assertEquals("compactAll", t.action)
    }


    @Test
    fun `an unknown id with neither flag still falls back safely`() {
        val t = tap(id = "future:thing", title = "Future")
        assertTrue(t.unrecognized)
        assertEquals(null, t.action)
    }


    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val vmSrc by lazy { src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }
    private val slashExtSrc by lazy { src("src/main/java/com/openminis/app/ui/chat/ChatViewModelSlashExt.kt") }

    @Test
    fun `the dispatcher guard accepts both row kinds`() {
        assertTrue(
            "executeSlashCommand must route MCP rows down the composer-fill path",
            vmSrc.contains("if (cmd.isSkill || cmd.isMcp)"),
        )
    }

    @Test
    fun `isMcp has a reader outside the row builder`() {
        assertTrue(
            "ChatViewModelSlashExt writes isMcp — that is the producer",
            slashExtSrc.contains("isMcp = true"),
        )
        assertTrue(
            "ChatViewModel must CONSUME isMcp, or MCP rows go undispatched again",
            vmSrc.contains("cmd.isMcp"),
        )
    }

    @Test
    fun `the log distinguishes an mcp tap from a skill tap`() {
        val body = vmSrc.substringAfter("if (cmd.isSkill || cmd.isMcp) {").substringBefore("savedInputBeforeSlash = null")
        assertTrue(
            "the composer-fill log must name the row kind",
            body.contains("""val kind = if (cmd.isMcp) "mcp" else "skill""""),
        )
        assertTrue(body.contains("[Slash] tap \$kind id="))
    }

    @Test
    fun `MCP rows are titled with the server id, so the composer gets the server name`() {
        // The composer text is "/${cmd.title} ", so the title IS what the user
        // ends up typing. It must be the server id (`test-mcp`), not a label.
        val block = slashExtSrc.substringAfter("val mcpRows:").substringBefore("val all =")
        assertTrue("MCP row id must be the mcp: namespace", block.contains("""id = "mcp:${'$'}{server.id}""""))
        assertTrue("MCP row title must be the raw server id", block.contains("title = server.id"))
    }
}
