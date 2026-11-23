package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatToleranceTest {

    private fun manifest(json: String) =
        BackupFormat.json.decodeFromString(BackupManifest.serializer(), json)

    @Test
    fun `unknown manifest fields from a future writer are ignored`() {
        val m = manifest(
            """
            {
              "format": "minisbak/1",
              "backup_id": "abc",
              "device_name": "Pixel 6",
              "future_field": {"nested": [1, 2, 3]},
              "categories": {"chats": {"entries": 5, "bytes": 99, "encrypted": true,
                                       "unknown_stat": "ignored"}}
            }
            """.trimIndent()
        )
        assertEquals("abc", m.backupId)
        assertEquals("Pixel 6", m.deviceName)
        assertEquals(5, m.categories["chats"]?.entries)
        assertEquals(99L, m.categories["chats"]?.bytes)
    }

    @Test
    fun `a manifest with almost nothing in it still decodes`() {
        val m = manifest("""{"format":"minisbak/1"}""")
        assertEquals(BackupFormat.CURRENT, m.format)
        assertEquals("Unknown device", m.deviceName)
        assertEquals(emptyMap<String, BackupManifest.CategoryStat>(), m.categories)
        assertNull(m.encryption)
        assertNull(m.snapshotAt)
        assertNull(m.limits.maxFileBytes)
        assertEquals(0, m.limits.skippedFiles)
    }

    @Test
    fun `encryption kdf without alg or salt is refused rather than defaulted`() {
        var threw = false
        try {
            manifest(
                """{"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/1",
                   "kdf":{"iterations":600000},"verifier":"x"}}"""
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("a KDF missing alg/salt must not decode with guessed values", threw)
    }

    @Test
    fun `manifest round-trips through snake_case wire keys`() {
        val m = BackupManifest(
            createdAt = "2026-08-16T10:00:00Z",
            snapshotAt = "2026-08-16T09:59:00Z",
            deviceName = "Pixel 6",
            backupId = "id-1",
            limits = BackupManifest.Limits(maxFileBytes = 1024, skippedFiles = 2, skippedBytes = 4096),
            categories = mapOf(
                "providers" to BackupManifest.CategoryStat(
                    entries = 3, bytes = 12, encrypted = true, includesCredentials = true
                )
            ),
        )
        val encoded = BackupFormat.json.encodeToString(BackupManifest.serializer(), m)
        assertTrue("created_at" in encoded)
        assertTrue("snapshot_at" in encoded)
        assertTrue("device_name" in encoded)
        assertTrue("backup_id" in encoded)
        assertTrue("max_file_bytes" in encoded)
        assertTrue("skipped_files" in encoded)
        assertTrue("includes_credentials" in encoded)
        assertTrue("createdAt" !in encoded)
        assertTrue("deviceName" !in encoded)
        assertTrue("backupId" !in encoded)

        assertEquals(m, manifest(encoded))
    }

    @Test
    fun `a corrupt file-index line does not sink the surrounding lines`() {
        val lines = listOf(
            """{"path":"a.txt","size":1,"sha256":"aa","category":"chats"}""",
            """{ this is not json """,
            """{"path":"b.txt","size":2,"sha256":"bb","category":"chats","brand_new":true}""",
        )
        var ok = 0
        var bad = 0
        for (line in lines) {
            val entry = runCatching {
                BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), line)
            }.getOrNull()
            if (entry == null) bad += 1 else ok += 1
        }
        assertEquals(2, ok)
        assertEquals(1, bad)
    }

    @Test
    fun `size tombstone keeps the path and original size`() {
        val e = BackupFileIndexEntry.sizeSkipped("<sid>/offloads/big.zip", 2_147_483_648, BackupCategory.CHATS)
        assertEquals("size", e.skipped)
        assertNull(e.sha256)
        assertEquals(2_147_483_648, e.size)
        val encoded = BackupFormat.json.encodeToString(BackupFileIndexEntry.serializer(), e)
        assertTrue("sha256" !in encoded)
    }

    @Test
    fun `voice corrections is excluded from new backups but still parses`() {
        assertTrue(BackupCategory.VOICE_CORRECTIONS !in BackupCategory.backupable)
        assertEquals(BackupCategory.VOICE_CORRECTIONS, BackupCategory.fromKey("voice_corrections"))
        assertNull(BackupCategory.fromKey("something_from_the_future"))
    }
}
