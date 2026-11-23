package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePerfGuardTest {

    private val src by lazy { ProductionSources.read("backup/BackupImporter.kt") }
    private val ui by lazy {
        ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    }

    @Test
    fun `no per-record session query survives in the hot loops`() {
        assertEquals(
            "dao.getSession(sessionId) must not appear in a per-record loop",
            0,
            Regex("""dao\.getSession\(sessionId\)""").findAll(src).count(),
        )
        assertTrue(
            "the parent check must be an in-memory set lookup",
            src.contains("sessionId !in restoredSessionIds"),
        )
    }

    @Test
    fun `every bulk loop runs inside a transaction`() {
        assertEquals(
            "all four bulk loops must be batched",
            4,
            Regex("""db\.withTransaction""").findAll(src).count(),
        )
    }

    @Test
    fun `the messages loop specifically is batched`() {
        val head = src.substringBefore("""readJsonl(dataDir, "messages")""")
        assertTrue(
            "a transaction must open before the messages loop",
            head.trimEnd().endsWith("db.withTransaction {"),
        )
    }

    @Test
    fun `the last-message rebuild is batched too`() {
        val i = src.indexOf("for (sid in restoredSessionIds)")
        assertTrue("preview rebuild loop not found", i > 0)
        assertTrue(
            "the rebuild loop must sit inside a transaction",
            src.substring(0, i).trimEnd().endsWith("db.withTransaction {"),
        )
    }

    @Test
    fun `the busy indicator is determinate, not an infinite animation`() {
        assertTrue(
            "the indicator must take a progress lambda",
            ui.contains("progress = { fraction ?: 0f }"),
        )
        assertTrue(
            "a fraction must be computed from done/total",
            ui.contains("p.done.toFloat() / total"),
        )
    }

    @Test
    fun `progress still throttles to every 200 records`() {
        assertTrue("the 200-record beat must remain", src.contains("++seen % 200 == 0"))
        assertTrue("it must still drive the progress emit", src.contains("onCount?.invoke(seen)"))
    }

    @Test
    fun `the WAL is truncated after the big transactions commit`() {
        assertTrue(
            "a TRUNCATE checkpoint must run after the chats import",
            src.contains("PRAGMA wal_checkpoint(TRUNCATE)"),
        )
        val i = src.indexOf("PRAGMA wal_checkpoint(TRUNCATE)")
        assertTrue(
            "the checkpoint must be wrapped in runCatching",
            src.lastIndexOf("runCatching", i) > src.lastIndexOf("applyFileResult", i),
        )
    }

    @Test
    fun `transactions do not swallow the orphan diagnostics`() {
        val warn = src.indexOf("dropped \$orphanedMessages message(s)")
        val close = src.indexOf("if (orphanedMessages > 0)")
        assertTrue("orphan warning missing", warn > 0)
        assertTrue("orphan check missing", close > 0)
        assertTrue("the warning must follow the messages loop", close < warn)
    }
}
