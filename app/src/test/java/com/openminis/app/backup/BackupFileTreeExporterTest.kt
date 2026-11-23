package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BackupFileTreeExporterTest {

    private lateinit var tmp: File
    private lateinit var staging: File
    private lateinit var source: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("minisbak-tree", "").apply { delete(); mkdirs() }
        staging = File(tmp, "staging").apply { mkdirs() }
        source = File(tmp, "source").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun exporter(
        maxFileBytes: Long? = null,
        snapshotAt: Long = Long.MAX_VALUE,
    ): Triple<BackupFileTreeExporter, BackupBlobStore, File> {
        val blobs = BackupBlobStore(staging, maxFileBytes)
        val indexFile = File(staging, "files.index.jsonl")
        val writer = BackupFileIndexWriter(indexFile)
        return Triple(BackupFileTreeExporter(blobs, writer, snapshotAt), blobs, indexFile)
    }

    private fun readIndex(file: File): List<BackupFileIndexEntry> =
        file.readLines().filter { it.isNotBlank() }.map {
            BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), it)
        }

    @Test
    fun `walks a tree into blobs and index entries`() {
        File(source, "attachments").mkdirs()
        File(source, "attachments/a.png").writeBytes(byteArrayOf(1, 2, 3))
        File(source, "workspace/deep").mkdirs()
        File(source, "workspace/deep/notes.md").writeText("hello")

        val (trees, blobs, indexFile) = exporter()
        val r = trees.export(source, "chats/sid1", BackupCategory.CHATS, "sid1")

        assertEquals(2, r.filesIncluded)
        assertEquals(8L, r.bytesIncluded)
        assertEquals(2, blobs.blobIndex.size)

        val paths = readIndex(indexFile).map { it.path }.toSet()
        assertTrue("chats/sid1/attachments/a.png" in paths)
        assertTrue("chats/sid1/workspace/deep/notes.md" in paths)
        assertTrue(readIndex(indexFile).all { it.category == BackupCategory.CHATS.key })
    }

    @Test
    fun `records empty directories so the tree can be rebuilt`() {
        File(source, "offloads/empty-dir").mkdirs()
        val (trees, _, indexFile) = exporter()
        trees.export(source, "chats/sid1", BackupCategory.CHATS)

        val dirs = readIndex(indexFile).filter { it.isDirectory == true }
        assertEquals(listOf("chats/sid1/offloads/empty-dir"), dirs.map { it.path })
    }

    @Test
    fun `an over-cap file becomes a tombstone, not a silent drop`() {
        File(source, "small.txt").writeBytes(ByteArray(10))
        File(source, "huge.bin").writeBytes(ByteArray(500))

        val (trees, blobs, indexFile) = exporter(maxFileBytes = 100)
        val r = trees.export(source, "shared", BackupCategory.SHARED_FILES)

        assertEquals(1, r.filesIncluded)
        assertEquals(1, r.filesSkipped)
        assertEquals(1, blobs.skippedFiles)

        val tombstone = readIndex(indexFile).single { it.skipped != null }
        assertEquals("shared/huge.bin", tombstone.path)
        assertEquals("size", tombstone.skipped)
        assertEquals("the true size must survive so the UI can report it", 500L, tombstone.size)
        assertEquals("a tombstone carries no content hash", null, tombstone.sha256)
    }

    @Test
    fun `the snapshot cut-off excludes newer files and keeps everything else`() {
        val cutoff = 1_000_000L
        File(source, "old.txt").apply { writeText("old"); setLastModified(cutoff - 10_000) }
        File(source, "exactly-at.txt").apply { writeText("at"); setLastModified(cutoff) }
        File(source, "new.txt").apply { writeText("new"); setLastModified(cutoff + 10_000) }

        val (trees, _, indexFile) = exporter(snapshotAt = cutoff)
        val r = trees.export(source, "shared", BackupCategory.SHARED_FILES)

        val paths = readIndex(indexFile).map { it.path }.toSet()
        assertTrue("a file older than the cut-off is included", "shared/old.txt" in paths)
        assertTrue("a file exactly at the cut-off is included", "shared/exactly-at.txt" in paths)
        assertTrue("a file after the cut-off is excluded", "shared/new.txt" !in paths)
        assertEquals(1, r.filesAfterSnapshot)
        assertTrue(readIndex(indexFile).none { it.skipped != null })
    }

    @Test
    fun `skips backup artifacts so packages never nest`() {
        File(source, "notes.md").writeText("keep me")
        File(source, "backup-20260816-1200-abc.minisbak").writeText("previous package")
        File(source, "Backups").mkdirs()
        File(source, "Backups/backup-older.minisbak").writeText("older package")
        File(source, "Backups/stray.txt").writeText("also inside Backups")

        val (trees, _, indexFile) = exporter()
        val r = trees.export(source, "shared", BackupCategory.SHARED_FILES)

        val paths = readIndex(indexFile).map { it.path }
        assertEquals(listOf("shared/notes.md"), paths)
        assertEquals(1, r.filesIncluded)
    }

    @Test
    fun `duplicate content is indexed twice but stored once`() {
        val shared = "the same attachment".toByteArray()
        File(source, "s1").mkdirs()
        File(source, "s2").mkdirs()
        File(source, "s1/a.bin").writeBytes(shared)
        File(source, "s2/b.bin").writeBytes(shared)

        val (trees, blobs, indexFile) = exporter()
        val r = trees.export(source, "chats", BackupCategory.CHATS)

        assertEquals("both paths must appear in the tree", 2, r.filesIncluded)
        assertEquals("but the bytes are stored once", 1, blobs.blobIndex.size)
        val entries = readIndex(indexFile).filter { it.sha256 != null }
        assertEquals(2, entries.size)
        assertEquals("both index entries point at the same blob",
            1, entries.map { it.sha256 }.toSet().size)
    }

    @Test
    fun `a missing root is not an error`() {
        val (trees, _, _) = exporter()
        val r = trees.export(File(tmp, "does-not-exist"), "skills", BackupCategory.SKILLS)
        assertEquals(0, r.filesIncluded)
        assertEquals(0, r.filesSkipped)
    }
}
