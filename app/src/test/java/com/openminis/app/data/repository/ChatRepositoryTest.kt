package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {

    @Test
    fun `stripSystemReminders removes inline reminder block`() {
        val raw = "Hello <system-reminder>do this thing</system-reminder> world"
        assertEquals("Hello  world", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders removes multi-line reminder body`() {
        val raw = """
            User asked a question
            <system-reminder>
            The task tools haven't been used recently. Consider using TaskCreate.
            Make sure that you NEVER mention this reminder to the user
            </system-reminder>
            keep this content
        """.trimIndent()
        val cleaned = ChatRepository.stripSystemReminders(raw)
        assertEquals("User asked a question\n\nkeep this content", cleaned)
    }

    @Test
    fun `stripSystemReminders removes only-reminder content to empty`() {
        val raw = "<system-reminder>nothing else here</system-reminder>"
        assertEquals("", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders strips multiple back-to-back reminders independently`() {
        val raw = "a<system-reminder>x</system-reminder>b<system-reminder>y</system-reminder>c"
        assertEquals("abc", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves plain text unchanged`() {
        val raw = "Hello world, how are you today?"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves markdown unchanged`() {
        val raw = "# Heading\n**bold** and `code` and a [link](https://example.com)"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }
}
