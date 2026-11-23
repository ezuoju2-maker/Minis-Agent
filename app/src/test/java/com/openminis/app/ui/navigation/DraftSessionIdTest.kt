package com.openminis.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftSessionIdTest {

    @Test
    fun `a freshly minted draft id is recognised as a draft`() {
        assertTrue(isDraftSessionId(newDraftSessionId()))
    }

    @Test
    fun `a persisted UUID is not a draft`() {
        assertFalse(isDraftSessionId("1a6577c8-9ee6-4183-9cb7-8cd0995eb8d8"))
    }

    @Test
    fun `null is not a draft`() {
        assertFalse(isDraftSessionId(null))
    }

    @Test
    fun `an empty id is not a draft`() {
        assertFalse(isDraftSessionId(""))
    }

    @Test
    fun `each draft id is unique`() {
        val ids = List(50) { newDraftSessionId() }
        assertEquals50Distinct(ids)
    }

    private fun assertEquals50Distinct(ids: List<String>) {
        assertTrue("draft ids must be unique", ids.toSet().size == ids.size)
    }

    @Test
    fun `a draft id does not look like a persisted id`() {
        val draft = newDraftSessionId()
        assertNotEquals(draft, draft.removePrefix("__new__"))
        assertFalse(isDraftSessionId(draft.removePrefix("__new__")))
    }

    @Test
    fun `an id merely containing the marker is not a draft`() {
        assertFalse(isDraftSessionId("abc__new__def"))
    }
}
