package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.ConcurrentModificationException

class UiMessagesSubListCmeTest {

    private fun backingList(n: Int = 100) = ArrayList<String>().apply {
        repeat(n) { add("m$it") }
    }

    @Test
    fun `a live subList view throws CME on equals after the backing list changes`() {
        val backing = backingList()
        val emitted: List<String> = backing.subList(60, backing.size)

        backing.add("m100")

        assertThrows(ConcurrentModificationException::class.java) {
            emitted == listOf("anything")
        }
    }

    @Test
    fun `the toList copy survives the same mutation`() {
        val backing = backingList()
        val emitted: List<String> = backing.subList(60, backing.size).toList()

        backing.add("m100")

        assertEquals(40, emitted.size)
        assertEquals("m60", emitted.first())
        assertEquals("m99", emitted.last())
        assertEquals(false, emitted == listOf("anything"))
    }

    @Test
    fun `storing a live view as the new list poisons every later append`() {
        val backing = backingList()
        val kept: List<String> = backing.subList(0, 50)

        backing.add("m100")

        assertThrows(ConcurrentModificationException::class.java) {
            kept == listOf("anything")
        }
    }

    @Test
    fun `truncating with toList yields an independent list`() {
        val backing = backingList()
        val kept: List<String> = backing.subList(0, 50).toList()

        backing.add("m100")
        backing.clear()

        assertEquals(50, kept.size)
        assertEquals("m49", kept.last())
    }

    @Test
    fun `the under-cap fast path stays identity-equal`() {
        val raw: List<String> = backingList(10)
        val cap = 40
        val threshold = 50

        val emitted = if (raw.size <= threshold || raw.size <= cap) raw
        else raw.subList(raw.size - cap, raw.size).toList()

        assertEquals(true, emitted === raw)
    }
}
