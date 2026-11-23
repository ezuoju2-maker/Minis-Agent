package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDestinationSaveStateTest {

    private enum class Screen { BROWSER, ADD_FORM, LIST }

    private class DestinationsUiState {
        var browsing = false
        var adding = false
        var justSaved: String? = null

        val screen: Screen
            get() = when {
                browsing -> Screen.BROWSER
                adding -> Screen.ADD_FORM
                else -> Screen.LIST
            }

        fun beginAdd() {
            adding = true
            justSaved = null
        }

        fun connected() {
            browsing = true
        }

        fun saveSucceeded(name: String) {
            browsing = false
            adding = false
            justSaved = name
        }

        fun saveFailed() {
        }

        fun cancel() {
            browsing = false
            adding = false
        }
    }

    @Test
    fun `successful save returns to the list and confirms the name`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()
        assertEquals(Screen.BROWSER, s.screen)

        s.saveSucceeded("nas-webdav")

        assertEquals(Screen.LIST, s.screen)
        assertEquals("nas-webdav", s.justSaved)
    }

    @Test
    fun `save must clear adding too, not just the browser`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()

        s.browsing = false
        assertEquals(
            "clearing browse alone leaves the user on the add form",
            Screen.ADD_FORM, s.screen,
        )

        s.saveSucceeded("nas-webdav")
        assertEquals(Screen.LIST, s.screen)
    }

    @Test
    fun `a failed save keeps the user on the form with no success message`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()

        s.saveFailed()

        assertEquals(Screen.BROWSER, s.screen)
        assertNull("a failure must never show the saved confirmation", s.justSaved)
    }

    @Test
    fun `cancel returns to the list without claiming a save`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()

        s.cancel()

        assertEquals(Screen.LIST, s.screen)
        assertNull(s.justSaved)
    }

    @Test
    fun `beginning another add drops the stale confirmation`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()
        s.saveSucceeded("first")
        assertTrue(s.justSaved != null)

        s.beginAdd()

        assertEquals(Screen.ADD_FORM, s.screen)
        assertNull(s.justSaved)
        assertFalse(s.browsing)
    }
}
