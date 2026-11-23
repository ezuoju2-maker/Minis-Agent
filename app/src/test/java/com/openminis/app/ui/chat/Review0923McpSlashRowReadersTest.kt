package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Review0923McpSlashRowReadersTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `BUG the tap handler refocuses the composer for MCP rows too`() {
        val screen = src("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        val tap = screen.indexOf("viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))")
        assertTrue("tap handler not found", tap >= 0)
        val window = screen.substring(tap, minOf(screen.length, tap + 1500))
        val focusGuard = window.indexOf("inputFocusRequester.requestFocus()")
        assertTrue("focus call not found near the tap handler", focusGuard >= 0)
        val guard = window.substring(0, focusGuard)
        assertTrue(
            "the post-tap focus/IME block must treat isMcp like isSkill",
            guard.contains("isMcp"),
        )
    }
}
