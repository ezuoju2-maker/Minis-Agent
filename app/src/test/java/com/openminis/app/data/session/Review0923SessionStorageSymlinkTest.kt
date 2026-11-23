package com.openminis.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class Review0923SessionStorageSymlinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) { f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(bytes)) }

    @Test
    fun `BUG a directory symlink in the workspace does not delete the target's contents`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        val otherFile = File(filesDir, "minis-sessions/B/workspace/precious.txt")
        write(otherFile, 99)
        Files.createSymbolicLink(
            File(filesDir, "minis-sessions/A/workspace/peer").toPath(),
            File(filesDir, "minis-sessions/B/workspace").toPath(),
        )

        storage.deleteFilesDetailed("A")

        assertTrue("session B's file must survive clearing session A", otherFile.exists())
    }

    @Test
    fun `a file symlink is removed as a link, the target stays`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        val target = File(filesDir, "minis-global/shared/keep.bin")
        write(target, 5)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        Files.createSymbolicLink(
            File(filesDir, "minis-sessions/A/workspace/link.bin").toPath(),
            target.toPath(),
        )

        val r = storage.deleteFilesDetailed("A")

        assertTrue(r.isComplete)
        assertTrue(target.exists())
        assertFalse(File(filesDir, "minis-sessions/A").exists())
        assertEquals(10L, r.freed)
    }

    @Test
    fun `BUG restoreWritable does not chmod a read-only directory outside the session`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        val outside = File(filesDir, "minis-global/skills/pinned")
        write(File(outside, "SKILL.md"), 3)
        outside.setWritable(false, false)
        assumeFalse("root/FUSE ignores directory mode bits", outside.canWrite())
        try {
            Files.createSymbolicLink(
                File(filesDir, "minis-sessions/A/workspace/skills").toPath(),
                outside.toPath(),
            )
            storage.deleteFilesDetailed("A")
            assertFalse("a directory outside the session must keep its mode", outside.canWrite())
        } finally {
            outside.setWritable(true, true)
        }
    }

    @Test
    fun `freed never exceeds the size the screen reported`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        write(File(filesDir, "media/2026/09/23/A/img.png"), 20)
        val outside = File(filesDir, "minis-sessions/B/workspace")
        write(File(outside, "big.bin"), 1000)
        Files.createSymbolicLink(File(filesDir, "minis-sessions/A/workspace/peer").toPath(), outside.toPath())

        val reported = storage.minisSize("A") + storage.mediaSize("A")
        val r = storage.deleteFilesDetailed("A")

        assertEquals(30L, reported)
        assertTrue("freed=${r.freed} must be <= reported=$reported", r.freed <= reported)
    }
}
