package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClearChatTouchesSessionTest {

    private val clearChatBody: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("ChatViewModel.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        val src = f.readText()
        val start = src.indexOf("fun clearChat() {")
        assertTrue("clearChat() not found — was it renamed?", start >= 0)
        val end = src.indexOf("// ─── Share Injection", start)
        assertTrue("could not bound clearChat()", end > start)
        src.substring(start, end)
    }

    @Test
    fun `clearChat refreshes the session row timestamp`() {
        assertTrue(
            "clearChat() must bump updated_at (via updateLastMessage or touchSession), " +
                "or a cleared chat stays sorted under its pre-clear date",
            clearChatBody.contains("updateLastMessage(") ||
                clearChatBody.contains("touchSession("),
        )
    }

    @Test
    fun `clearChat drops the stale last-message preview`() {
        assertTrue(
            "clearChat() must clear last_message, not just the timestamp",
            Regex("""updateLastMessage\(\s*\w+\s*,\s*null""").containsMatchIn(clearChatBody),
        )
    }

    @Test
    fun `clearChat still deletes messages and compact markers`() {
        assertTrue(clearChatBody.contains("deleteMessages("))
        assertTrue(clearChatBody.contains("deleteCompactMarkers("))
    }

    @Test
    fun `clearChat does not delete the session row`() {
        assertTrue(
            "clearChat() must never delete the session row",
            !clearChatBody.contains("deleteSession("),
        )
    }
}
