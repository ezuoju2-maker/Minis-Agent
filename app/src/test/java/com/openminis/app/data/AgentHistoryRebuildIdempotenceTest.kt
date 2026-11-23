package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHistoryRebuildIdempotenceTest {

    private fun row(id: String, role: LLMMessage.Role, text: String) = LLMMessage(
        role = role,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
        dbMessageId = id,
    )

    private fun dbRows(turns: Int): List<LLMMessage> =
        (1..turns).flatMap { i ->
            listOf(
                row("u$i", LLMMessage.Role.USER, "say MESSAGE-$i"),
                row("a$i", LLMMessage.Role.ASSISTANT, "MESSAGE-$i Pika!"),
            )
        }

    private fun rebuild(agentHistory: MutableList<LLMMessage>, loaded: List<LLMMessage>) {
        agentHistory.clear()
        agentHistory.addAll(loaded)
    }


    @Test
    fun `rebuilding twice leaves the same history as rebuilding once`() {
        val db = dbRows(10)
        val history = mutableListOf<LLMMessage>()

        rebuild(history, db)
        val afterColdStart = history.toList()

        rebuild(history, db)

        assertEquals(
            "rebuilding twice must not grow the history. Before the fix this was " +
                "40 (every row duplicated) instead of 20.",
            db.size,
            history.size,
        )
        assertEquals(
            "a revert must leave exactly what a cold start would leave",
            afterColdStart,
            history.toList(),
        )
    }

    @Test
    fun `rebuilt history matches the DB row for row, one to one`() {
        val db = dbRows(10)
        val history = mutableListOf<LLMMessage>()
        repeat(3) { rebuild(history, db) }

        assertEquals("history must stay 1:1 with the DB", db.size, history.size)
        assertEquals(
            "row ids must line up with the DB in order",
            db.map { it.dbMessageId },
            history.map { it.dbMessageId },
        )
        val ids = history.mapNotNull { it.dbMessageId }
        assertEquals(
            "no dbMessageId may appear twice — a repeated id IS the duplication bug",
            ids.size,
            ids.toSet().size,
        )
    }

    @Test
    fun `rebuilding onto a populated list discards the stale contents`() {
        val db = dbRows(3)
        val history = mutableListOf<LLMMessage>()
        rebuild(history, db)

        history.add(row("u99", LLMMessage.Role.USER, "say LATER"))
        assertEquals(db.size + 1, history.size)

        val dbAfter = db + row("u99", LLMMessage.Role.USER, "say LATER")
        rebuild(history, dbAfter)

        assertEquals(
            "the in-memory tail must be replaced by the DB's version, not appended to",
            dbAfter.size,
            history.size,
        )
        assertTrue(
            "the later turn must appear exactly once",
            history.count { it.dbMessageId == "u99" } == 1,
        )
    }


    @Test
    fun `an append-only rebuild is what the invariant catches`() {
        val db = dbRows(10)
        val history = mutableListOf<LLMMessage>()

        fun buggyRebuild(loaded: List<LLMMessage>) = history.addAll(loaded)
        buggyRebuild(db)
        buggyRebuild(db)

        assertEquals("the pre-fix shape duplicates — this is what we must never ship", 40, history.size)
        val ids = history.mapNotNull { it.dbMessageId }
        assertTrue("and it produces repeated dbMessageIds", ids.size != ids.toSet().size)
    }
}
