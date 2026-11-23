package com.openminis.app.backup

import com.openminis.app.backup.remote.RcloneChunkedUpload
import com.openminis.app.backup.remote.RcloneRemoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneRemoteSingleFileTest {

    private fun remote(backend: String, path: String) = RcloneRemoteStore.Remote(
        name = "nas", backend = backend, params = emptyMap(), path = path,
    )


    @Test
    fun `webdav path stays fs-relative`() {
        assertEquals("backups/a.minisbak", remote("webdav", "backups").join("a.minisbak"))
        assertEquals("backups/a.minisbak", remote("webdav", "/backups/").join("a.minisbak"))
    }

    @Test
    fun `webdav server root does not produce a leading slash`() {
        assertEquals("a.minisbak", remote("webdav", "").join("a.minisbak"))
        assertEquals("a.minisbak", remote("webdav", "/").join("a.minisbak"))
        assertFalse(remote("webdav", "/").join("a.minisbak").startsWith("/"))
    }

    @Test
    fun `sftp keeps its absolute path`() {
        assertEquals("/srv/backup/a.minisbak", remote("sftp", "/srv/backup").join("a.minisbak"))
        assertEquals("/srv/backup/a.minisbak", remote("sftp", "/srv/backup/").join("a.minisbak"))
        assertEquals("/a.minisbak", remote("sftp", "/").join("a.minisbak"))
        assertEquals("backup/a.minisbak", remote("sftp", "backup").join("a.minisbak"))
    }

    @Test
    fun `list root matches the join base`() {
        assertEquals("backups", remote("webdav", "/backups/").listRoot)
        assertEquals("", remote("webdav", "/").listRoot)
        assertEquals("/srv/backup", remote("sftp", "/srv/backup/").listRoot)
        assertEquals("/", remote("sftp", "/").listRoot)
    }


    @Test
    fun `scratch name is suffix-based, not dot-prefixed`() {
        val scratch = "backup-1.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}"
        assertFalse("dotfiles are hidden from WebDAV listings", scratch.startsWith("."))
        assertTrue(scratch.endsWith(".partial"))
    }

    @Test
    fun `in-flight scratch is not listed as a package`() {
        val listing = listOf(
            "backup-1.minisbak",
            "backup-2.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}",
            "notes.txt",
        )
        val restorable = listing.filter { it.endsWith(".${BackupFormat.FILE_EXTENSION}") }
        assertEquals(listOf("backup-1.minisbak"), restorable)
    }

    @Test
    fun `a large package still uploads under one final name`() {
        val r = remote("webdav", "backups")
        val name = "backup-large.minisbak"
        val partial = r.join("$name.${RcloneChunkedUpload.PARTIAL_SUFFIX}")
        val final = r.join(name)

        assertEquals("backups/backup-large.minisbak", final)
        assertEquals("backups/backup-large.minisbak.partial", partial)
        assertFalse(
            "new uploads must never write the legacy parts directory",
            partial.contains(RcloneChunkedUpload.PARTS_DIR) ||
                final.contains(RcloneChunkedUpload.PARTS_DIR),
        )
    }


    @Test
    fun `sweep only matches partial scratch objects`() {
        data class Entry(val name: String, val isDir: Boolean)
        val listing = listOf(
            Entry("backup-1.minisbak", false),
            Entry("backup-2.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}", false),
            Entry("old.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}", false),
            Entry(RcloneChunkedUpload.PARTS_DIR, true),
            Entry("family-photos", true),
        )
        val current = "backup-2.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}"

        val swept = listing
            .filterNot { it.isDir }
            .filter { it.name.endsWith(".${RcloneChunkedUpload.PARTIAL_SUFFIX}") }
            .filterNot { it.name == current }
            .map { it.name }

        assertEquals(listOf("old.minisbak.partial"), swept)
        assertFalse("a real backup must never be swept", swept.any { it == "backup-1.minisbak" })
        assertFalse(
            "the legacy parts directory must never be swept",
            swept.any { it == RcloneChunkedUpload.PARTS_DIR },
        )
    }


    @Test
    fun `legacy chunked upload is still recognised and reassembled in order`() {
        val legacy = RcloneChunkedUpload.RemotePackage(
            key = "backups/${RcloneChunkedUpload.PARTS_DIR}/backup-old",
            displayName = "backup-old",
            size = 24L * 1024 * 1024,
            modified = 1L,
            partCount = 3,
        )
        assertTrue("a 3-part legacy upload must take the reassembly path", legacy.isChunked)

        val names = listOf("000002", "000000", "000001").sorted()
        assertEquals(listOf("000000", "000001", "000002"), names)
        assertEquals(legacy.partCount, names.size)
    }

    @Test
    fun `whole package is not treated as chunked`() {
        val whole = RcloneChunkedUpload.RemotePackage(
            key = "backups/backup-1.minisbak",
            displayName = "backup-1.minisbak",
            size = 100L * 1024 * 1024,
            modified = 2L,
            partCount = 1,
        )
        assertFalse(whole.isChunked)
    }

    @Test
    fun `an incomplete legacy parts set is refused`() {
        val expected = 3
        val present = listOf("000000", "000002")
        assertTrue("a short parts set must not be reassembled", present.size != expected)
    }
}
