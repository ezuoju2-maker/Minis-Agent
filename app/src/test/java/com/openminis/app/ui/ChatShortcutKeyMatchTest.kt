package com.openminis.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatShortcutKeyMatchTest {

    private enum class Action { NEW_CHAT, SEARCH, NONE }

    private fun match(
        key: String,
        isKeyDown: Boolean = true,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ): Action {
        if (!isKeyDown) return Action.NONE
        if (!ctrl && !meta) return Action.NONE
        return when (key) {
            "N" -> Action.NEW_CHAT
            "F" -> Action.SEARCH
            else -> Action.NONE
        }
    }


    @Test
    fun `meta plus N opens a new chat`() {
        assertEquals(Action.NEW_CHAT, match("N", meta = true))
    }

    @Test
    fun `meta plus F opens search`() {
        assertEquals(Action.SEARCH, match("F", meta = true))
    }

    @Test
    fun `ctrl is accepted as well as meta`() {
        assertEquals(Action.NEW_CHAT, match("N", ctrl = true))
        assertEquals(Action.SEARCH, match("F", ctrl = true))
    }


    @Test
    fun `an unmodified letter is left alone`() {
        assertEquals(Action.NONE, match("N"))
        assertEquals(Action.NONE, match("F"))
    }

    @Test
    fun `key up does not fire`() {
        assertEquals(Action.NONE, match("N", isKeyDown = false, meta = true))
        assertEquals(Action.NONE, match("F", isKeyDown = false, meta = true))
    }

    @Test
    fun `other modified letters are not claimed`() {
        for (k in listOf("A", "C", "V", "X", "Z", "S")) {
            assertEquals("modifier+$k should pass through", Action.NONE, match(k, meta = true))
        }
    }

    @Test
    fun `the two chords do not collide`() {
        assertEquals(Action.NEW_CHAT, match("N", meta = true))
        assertEquals(Action.SEARCH, match("F", meta = true))
    }
}
