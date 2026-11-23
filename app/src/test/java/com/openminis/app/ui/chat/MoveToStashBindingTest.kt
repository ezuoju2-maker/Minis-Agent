package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MoveToStashBindingTest {

    @Before
    fun drainAnyLeftoverStash() {
        ChatViewModelStore.consumePendingTransfer("any-session")
        ChatViewModelStore.consumePendingTransfer("session-B")
    }

    @Test
    fun `target session drains its own stash`() {
        ChatViewModelStore.stashPendingTransfer(
            ChatViewModelStore.PendingTransfer(
                inputText = "payload",
                attachments = emptyList(),
                targetId = "session-B",
            ),
        )
        val got = ChatViewModelStore.consumePendingTransfer("session-B")
        assertEquals("payload", got?.inputText)
    }

    @Test
    fun `stash is drained exactly once`() {
        ChatViewModelStore.stashPendingTransfer(
            ChatViewModelStore.PendingTransfer(
                inputText = "payload",
                attachments = emptyList(),
                targetId = "session-B",
            ),
        )
        assertEquals("payload", ChatViewModelStore.consumePendingTransfer("session-B")?.inputText)
        assertNull(ChatViewModelStore.consumePendingTransfer("session-B"))
    }

    @Test
    fun `non-target session does not consume the stash, and the real target still can`() {
        ChatViewModelStore.stashPendingTransfer(
            ChatViewModelStore.PendingTransfer(
                inputText = "payload",
                attachments = emptyList(),
                targetId = "session-B",
            ),
        )
        assertNull(ChatViewModelStore.consumePendingTransfer("session-C"))
        assertEquals("payload", ChatViewModelStore.consumePendingTransfer("session-B")?.inputText)
    }

    @Test
    fun `stale stash is dropped rather than injected`() {
        ChatViewModelStore.stashPendingTransfer(
            ChatViewModelStore.PendingTransfer(
                inputText = "payload",
                attachments = emptyList(),
                targetId = "session-B",
                stashedAtMs = System.currentTimeMillis() - 301_000L,
            ),
        )
        assertNull(ChatViewModelStore.consumePendingTransfer("session-B"))
        assertNull(ChatViewModelStore.consumePendingTransfer("session-B"))
    }
}
