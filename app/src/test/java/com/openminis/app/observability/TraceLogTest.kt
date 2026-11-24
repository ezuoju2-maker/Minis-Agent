package com.openminis.app.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceLogTest {

    @Test
    fun `sequence numbers increase`() {
        val log = TraceLog()
        log.emit(1, "tool", "shell", 120, true, "ok")
        val e = log.emit(1, "tool", "file_read", 5, true, "ok")
        assertEquals(2, e.seq)
        assertEquals(2, log.size)
    }

    @Test
    fun `ndjson escapes quotes and control chars`() {
        val log = TraceLog()
        log.emit(1, "tool", "echo", 1, false, "say \"hi\"\npath\\to")
        val line = log.toNdjson().trim()
        assertTrue(line.startsWith("{") && line.endsWith("}"))
        assertTrue(line.contains("\\\"hi\\\""))
        assertTrue(line.contains("\"name\":\"echo\""))
        assertTrue(line.contains("\\\\"))
        assertTrue(!line.contains("\n"))
        assertTrue(line.contains("\"ok\":false"))
    }

    @Test
    fun `summary is flattened and clipped`() {
        val log = TraceLog(summaryLimit = 12)
        val e = log.emit(1, "tool", "shell", 1, true, "line1\nline2   line3 and more text")
        assertTrue(e.summary.length <= 12)
        assertTrue(e.summary.endsWith("…"))
        assertTrue(!e.summary.contains("\n"))
    }

    @Test
    fun `filters by turn and failure`() {
        val log = TraceLog()
        log.emit(1, "tool", "a", 1, true)
        log.emit(2, "tool", "b", 1, false)
        log.emit(2, "tool", "c", 1, true)
        assertEquals(2, log.ofTurn(2).size)
        assertEquals(1, log.failures().size)
        assertEquals("b", log.failures()[0].name)
    }

    @Test
    fun `since returns only newer events`() {
        val log = TraceLog()
        log.emit(1, "turn", "start", 0, true)
        val marker = log.lastSeq
        log.emit(1, "tool", "shell", 9, true)
        assertEquals(1, log.since(marker).size)
    }

    @Test
    fun `ring buffer drops the oldest`() {
        val log = TraceLog(maxEvents = 3)
        repeat(5) { log.emit(1, "tool", "n$it", 1, true) }
        assertEquals(3, log.size)
        assertEquals(3L, log.since(0).first().seq)
    }

    @Test
    fun `concurrent emits keep every sequence unique`() {
        val log = TraceLog(maxEvents = 10_000, summaryLimit = 30)
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(100) { i -> log.emit(1, "tool", "n$t-$i", i.toLong(), true, "ok") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(800, log.size)
        val seqs = log.since(0).map { it.seq }
        assertEquals(800, seqs.toSet().size)
        assertEquals(800L, seqs.maxOrNull())
    }

    @Test
    fun `clear empties the buffer`() {
        val log = TraceLog()
        log.emit(1, "tool", "x", 1, true)
        log.clear()
        assertEquals(0, log.size)
        assertEquals("", log.toNdjson())
    }
}
