package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashSendClearsInputTest {

    private class Composer(val withFix: Boolean) {
        var text: String = ""
        var savedInputBeforeSlash: String? = null
        var showSlashMenu: Boolean = false

        fun tapSlashButton() {
            val current = text
            if (current.startsWith("/")) {
                savedInputBeforeSlash = null
                showSlashMenu = true
                return
            }
            if (current.trim().isEmpty()) {
                savedInputBeforeSlash = null
                showSlashMenu = true
                text = "/"
                return
            }
            savedInputBeforeSlash = current
            showSlashMenu = true
            text = "/ $current"
        }

        fun onTextChanged(newText: String) {
            if (savedInputBeforeSlash != null) {
                if (!newText.startsWith("/")) {
                    savedInputBeforeSlash = null
                    showSlashMenu = false
                    return
                }
                showSlashMenu = true
                return
            }
            showSlashMenu = newText.startsWith("/") && newText.length < 30
        }

        fun send(): String {
            val sent = text
            if (withFix) {
                savedInputBeforeSlash = null
                showSlashMenu = false
            }
            text = ""
            return sent
        }

        fun tapOnChatList() {
            if (!showSlashMenu) return
            val saved = savedInputBeforeSlash
            savedInputBeforeSlash = null
            showSlashMenu = false
            if (saved != null) text = saved
        }
    }

    @Test
    fun `send while slash menu is open over text leaves the composer empty`() {
        val c = Composer(withFix = true)
        c.text = "hello"
        c.tapSlashButton()
        assertEquals("the '/' button prepends over the saved original", "/ hello", c.text)
        assertEquals("hello", c.savedInputBeforeSlash)

        val sent = c.send()
        assertEquals("/ hello", sent)
        assertEquals("composer must be empty after send", "", c.text)
        assertNull("the slash session must not survive a send", c.savedInputBeforeSlash)
        assertEquals(false, c.showSlashMenu)

        c.tapOnChatList()
        assertEquals("text must NOT come back after send", "", c.text)
    }

    @Test
    fun `without the fix the sent text is restored into the composer`() {
        val c = Composer(withFix = false)
        c.text = "hello"
        c.tapSlashButton()
        c.send()
        assertEquals("", c.text)
        c.tapOnChatList()
        assertEquals("this is the bug: the sent text reappears", "hello", c.text)
    }

    @Test
    fun `a normal send with no slash session still clears`() {
        val c = Composer(withFix = true)
        c.text = "plain message"
        val sent = c.send()
        assertEquals("plain message", sent)
        assertEquals("", c.text)
        assertNull(c.savedInputBeforeSlash)
    }

    @Test
    fun `dismissing the slash menu still restores the original text`() {
        val c = Composer(withFix = true)
        c.text = "keep me"
        c.tapSlashButton()
        assertEquals("/ keep me", c.text)
        val saved = c.savedInputBeforeSlash
        assertEquals("keep me", saved)
    }
}
