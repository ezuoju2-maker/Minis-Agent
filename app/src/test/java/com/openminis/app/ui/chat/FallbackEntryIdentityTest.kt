package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FallbackEntryIdentityTest {

    private data class Entry(val id: String, val modelId: String, val instanceLabel: String)

    private val entries = listOf(
        Entry("522ba52e/deepseek-v4-flash", "deepseek-v4-flash", "Bailian OpenAI"),
        Entry("58fe6f1f/deepseek-v4-flash", "deepseek-v4-flash", "DeekSeak"),
    )

    @Test
    fun `matching by model id is ambiguous and can pick the wrong instance`() {
        val servedBy = entries[1]
        val recovered = entries.find { it.modelId == servedBy.modelId }

        assertEquals("Bailian OpenAI", recovered?.instanceLabel)
        assertNotEquals(
            "the model-id lookup must be shown to disagree with reality",
            servedBy.instanceLabel,
            recovered?.instanceLabel,
        )
    }

    @Test
    fun `matching by entry id resolves the instance that actually served`() {
        val servedBy = entries[1]
        val recovered = entries.find { it.id == servedBy.id }

        assertEquals("DeekSeak", recovered?.instanceLabel)
        assertEquals(servedBy.id, recovered?.id)
    }

    @Test
    fun `entry-id lookup is stable regardless of list order`() {
        val servedBy = entries[1]
        for (list in listOf(entries, entries.reversed())) {
            assertEquals("DeekSeak", list.find { it.id == servedBy.id }?.instanceLabel)
        }
    }

    @Test
    fun `the persisted binding records the serving entry, not a same-model sibling`() {
        val servedBy = entries[1]
        val persisted = entries.find { it.id == servedBy.id }!!.id

        assertEquals("58fe6f1f/deepseek-v4-flash", persisted)
        assertNotEquals("522ba52e/deepseek-v4-flash", persisted)
    }
}
