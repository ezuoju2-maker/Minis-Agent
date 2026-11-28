package com.openminis.app.share

import com.openminis.app.data.db.ChatSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExportNameTest {

    private fun session(title: String?) = ChatSessionEntity(
        id = "s-${title.hashCode()}",
        title = title,
        modelId = "test-model",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun ordinaryTitleIsKept() {
        assertEquals("Refactor the parser", SessionExporter.baseName(listOf(session("Refactor the parser"))))
    }

    @Test
    fun separatorsAndControlCharactersBecomeDashes() {
        val name = SessionExporter.baseName(listOf(session("a/b:c*d?e\"f<g>h|i\nj\tk")))
        assertEquals("a-b-c-d-e-f-g-h-i-j-k", name)
    }

    @Test
    fun leadingDotsAndSpacesAreTrimmed() {
        val name = SessionExporter.baseName(listOf(session("  ..hidden  ")))
        assertEquals("hidden", name)
        assertFalse(name.startsWith("."))
    }

    @Test
    fun blankTitleFallsBackToSessionCount() {
        assertEquals("minis-sessions-1", SessionExporter.baseName(listOf(session(null))))
        assertEquals("minis-sessions-1", SessionExporter.baseName(listOf(session("   "))))
        assertEquals("minis-sessions-2", SessionExporter.baseName(listOf(session(null), session("b"))))
    }

    @Test
    fun longTitlesAreCapped() {
        val name = SessionExporter.baseName(listOf(session("x".repeat(200))))
        assertTrue(name.length <= 60)
    }
}
