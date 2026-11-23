package com.openminis.app.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupProgressLogTest {

    private fun entry(message: String, transient: Boolean = false, problem: Boolean = false) =
        BackupHistory.LogEntry(at = 0L, message = message, isProblem = problem, isTransient = transient)


    @Test
    fun `ten thousand percent lines leave one progress line and every phase line`() {
        val log = mutableListOf<BackupHistory.LogEntry>()
        BackupLogRules.append(log, entry("Exporting chats…"))
        BackupLogRules.append(log, entry("Sending to Folder…"))
        var n = 0
        for (pct in 0..100) {
            repeat(100) {
                BackupLogRules.append(log, entry("Saving to Folder… $pct%", transient = true))
                n++
            }
        }
        assertTrue(n >= 10_000)
        assertEquals(
            listOf("Exporting chats…", "Sending to Folder…", "Saving to Folder… 100%"),
            log.map { it.message },
        )
        assertTrue(log.last().isTransient)

        BackupLogRules.append(log, entry("Delivered to Folder"))
        assertEquals(
            "a phase line takes the progress line's place, as on iOS",
            listOf("Exporting chats…", "Sending to Folder…", "Delivered to Folder"),
            log.map { it.message },
        )
    }


    private fun iosLog(log: MutableList<Pair<String, Boolean>>, message: String, transient: Boolean) {
        if (log.lastOrNull()?.first == message) return
        if (log.lastOrNull()?.second == true) log.removeAt(log.lastIndex)
        log.add(message to transient)
    }

    @Test
    fun `android log rules produce the same sequence as ios log`() {
        val input = buildList {
            add("Starting…" to false)
            add("Starting…" to false)
            add("Exporting chats…" to false)
            for (p in listOf(0, 0, 1, 1, 1, 2, 5, 5, 9)) add("Uploading to NAS — $p%" to true)
            add("Uploading to NAS — 9%" to false)
            add("Delivery to NAS failed: timeout" to false)
            add("Saving to Folder… 3%" to true)
            add("Saving to Folder… 3%" to true)
            add("Saving to Folder… 4%" to true)
            add("Delivered to Folder" to false)
            add("Delivered to Folder" to false)
        }
        val ios = mutableListOf<Pair<String, Boolean>>()
        val android = mutableListOf<BackupHistory.LogEntry>()
        input.forEach { (m, t) ->
            iosLog(ios, m, t)
            BackupLogRules.append(android, entry(m, transient = t))
        }
        assertEquals(ios, android.map { it.message to it.isTransient })
        assertEquals(
            listOf("Starting…", "Exporting chats…", "Delivery to NAS failed: timeout", "Delivered to Folder"),
            android.map { it.message },
        )
    }


    @Test
    fun `percent gate admits a percent once and at most once a second`() {
        var now = 0L
        val gate = PercentGate(minIntervalMs = 1_000, clock = { now })
        var admitted = 0
        for (call in 0 until 260_000) {
            now = call.toLong()
            if (gate.admit(call / 2_600)) admitted++
        }
        assertTrue("one line per changed percent, throttled to 1 s: $admitted", admitted in 90..101)

        var t = 0L
        val fast = PercentGate(minIntervalMs = 1_000, clock = { t })
        assertTrue(fast.admit(0))
        assertFalse("same percent", fast.admit(0))
        t = 500
        assertFalse("changed but inside the interval", fast.admit(1))
        t = 1_000
        assertTrue(fast.admit(1))
    }


    @Test
    fun `ten thousand progress callbacks inside a second write at most twice and the last state lands`() {
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val written = mutableListOf<Int>()
        var state = 0
        val writer = ThrottledWriter(
            intervalMs = 1_000,
            clock = { now },
            schedule = { delay, block -> scheduled.add(delay to block) },
            write = { written.add(state) },
        )
        repeat(10_000) { i ->
            state = i + 1
            now = i / 10L
            writer.request()
        }
        assertEquals("leading write only, the rest coalesce", 1, written.size)
        assertEquals("one trailing write is scheduled, not 9,999", 1, scheduled.size)

        writer.close()
        assertTrue("≤ 2 writes for 10,000 callbacks: ${written.size}", written.size <= 2)
        assertEquals("the final state is on disk", 10_000, written.last())

        state = -1
        scheduled.forEach { it.second() }
        assertEquals(10_000, written.last())
    }

    @Test
    fun `a throttled progress line still reaches the record within the interval`() {
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val written = mutableListOf<Int>()
        var state = 0
        val writer = ThrottledWriter(1_000, { now }, { d, b -> scheduled.add(d to b) }, { written.add(state) })
        state = 1; writer.request()
        now = 200; state = 2; writer.request()
        assertEquals(listOf(1), written)
        assertEquals("trailing edge lands at the end of the interval", 800L, scheduled.single().first)
        now = 1_000
        scheduled.single().second()
        assertEquals("the running row's subtitle does not freeze on a throttled line", listOf(1, 2), written)
        now = 2_500; state = 3; writer.request()
        assertEquals("outside the interval: written at once", listOf(1, 2, 3), written)
    }


    @Test
    fun `controller routes progress through the ios rules, the gate and the throttle`() {
        val c = File("src/main/java/com/openminis/app/backup/BackupRunController.kt").readText()
        val start = c.substringAfter("fun start(").substringBefore("fun saveTo(")
        assertTrue("log goes through the rules", start.contains("BackupLogRules.append(log, entry)"))
        assertTrue("progress requests a throttled write", start.substringAfter("fun progress(").contains("historyWriter.request()"))
        assertFalse(
            "no unthrottled write per progress line",
            start.substringAfter("fun progress(").substringBefore("runJob =").contains("writeHistory("),
        )
        assertTrue("final write forced", start.substringAfter("} finally {").contains("historyWriter.close()"))

        val deliver = c.substringAfter("private fun deliverToDestinations(")
        val local = deliver.substringAfter("localDelivery.deliver(").substringBefore("} else {")
        assertTrue("percent gate on the 8 KB callback", local.contains("gate.admit("))
        assertTrue("local folder progress is transient", local.contains("$" + "pct%\", true)"))
        val rclone = deliver.substringAfter("uploader.upload(").substringBefore("outcomes.add(")
        assertTrue("rclone progress is transient", rclone.contains("$" + "pct%\", true)"))
        assertTrue("phase line is permanent", deliver.contains("onProgress(\"Sending to \${remote.name}…\", false)"))
    }


    @Test
    fun `a records file without isTransient decodes completely`() {
        val fixture = File(javaClass.classLoader!!.getResource("backup/records-v1.15-beta4.json")!!.toURI())
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val records = json.decodeFromString(ListSerializer(BackupHistory.Record.serializer()), fixture.readText())
        assertEquals(2, records.size)
        val r = records.first()
        assertEquals("0b1c2d3e-0000-4000-8000-00000000beta", r.id)
        assertEquals(BackupHistory.Status.SUCCEEDED, r.status)
        assertEquals(786_218_670L, r.totalBytes)
        assertEquals(listOf("minis"), r.destinations.map { it.name })
        assertEquals(5, r.log.size)
        assertTrue(r.log.none { it.isTransient })
        assertTrue(r.log.single { it.isProblem }.message.startsWith("Delivery"))
        assertEquals(BackupHistory.Status.FAILED, records[1].status)
        assertEquals(1, records[1].skippedEntries.size)
    }
}
