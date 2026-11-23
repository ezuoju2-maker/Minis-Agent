package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupZipTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("minisbak-zip", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun staging(): File = File(tmp, "staging").apply {
        mkdirs()
        File(this, "manifest.json").writeText("""{"format":"minisbak/1"}""")
        File(this, "data").mkdirs()
        File(this, "data/sessions.jsonl").writeText("{\"t\":\"session\",\"v\":1,\"d\":{}}\n")
        File(this, "blobs/ab").mkdirs()
        File(this, "blobs/ab/abcdef").writeBytes(ByteArray(4096) { it.toByte() })
    }

    @Test
    fun `a large member is stored regardless of how well it would compress`() {
        val st = staging()
        File(st, "data/huge.jsonl").writeBytes(ByteArray(40 * 1024 * 1024))

        val out = File(tmp, "p.minisbak")
        BackupZip.archive(st, out)

        java.util.zip.ZipFile(out).use { zf ->
            val huge = zf.entries().toList().first { it.name == "data/huge.jsonl" }
            assertEquals(
                "a member past the cap must stay STORED — iOS inflates whole",
                ZipEntry.STORED.toLong(), huge.method.toLong(),
            )
        }
    }

    @Test
    fun `entry names are package-relative with forward slashes`() {
        val out = File(tmp, "p.minisbak")
        BackupZip.archive(staging(), out)
        val names = BackupZip.listEntries(out).toSet()
        assertEquals(setOf("manifest.json", "data/sessions.jsonl", "blobs/ab/abcdef"), names)
    }

    @Test
    fun `round-trips content through archive and extract`() {
        val out = File(tmp, "p.minisbak")
        BackupZip.archive(staging(), out)
        val dest = File(tmp, "extracted")
        BackupZip.extract(out, dest)

        assertEquals("""{"format":"minisbak/1"}""", File(dest, "manifest.json").readText())
        assertTrue(File(dest, "blobs/ab/abcdef").readBytes().contentEquals(ByteArray(4096) { it.toByte() }))
    }

    @Test
    fun `an archive with more entries than the classic EOCD can count goes ZIP64 and round-trips`() {
        val dir = File(tmp, "many").apply { mkdirs() }
        for (i in 0..65_535) File(dir, "f$i").writeBytes(ByteArray(0))

        val out = File(tmp, "many.minisbak")
        BackupZip.archive(dir, out)

        val tail = tailOf(out)
        assertTrue("a ZIP64 EOCD record must be written", tail.indexOf(ZIP64_EOCD) >= 0)
        val eocd = tail.lastIndexOf(CLASSIC_EOCD)
        assertEquals("the classic count is the 0xFFFF sentinel iOS keys on",
            0xFFFF, u16(tail, eocd + 10))

        val dest = File(tmp, "many-out")
        BackupZip.extract(out, dest)
        assertEquals(65_536, dest.listFiles()!!.size)
    }

    @Test
    fun `a package over 4GB archives and extracts`() {
        val bigDir = System.getenv("MINIS_ZIP64_BIG_DIR")
        Assume.assumeTrue("set MINIS_ZIP64_BIG_DIR to run", bigDir != null)
        val work = File(bigDir!!).apply { mkdirs() }
        val st = File(work, "android-staging").apply { deleteRecursively(); mkdirs() }
        val big = 0x1_0000_0000L + 4096
        RandomAccessFile(File(st, "a-big.bin"), "rw").use { it.setLength(big) }
        File(st, "manifest.json").writeText("""{"format":"minisbak/1"}""")

        val out = File(work, "android-big.minisbak")
        BackupZip.archive(st, out)
        assertTrue(out.length() > big)
        assertTrue("a ZIP64 EOCD record must be written", tailOf(out).indexOf(ZIP64_EOCD) >= 0)

        val dest = File(work, "android-extract").apply { deleteRecursively() }
        try {
            BackupZip.extract(out, dest)
            assertEquals(big, File(dest, "a-big.bin").length())
            assertEquals("""{"format":"minisbak/1"}""", File(dest, "manifest.json").readText())
        } finally {
            dest.deleteRecursively()
            st.deleteRecursively()
        }
    }

    @Test
    fun `a package over 4GB written by iOS extracts`() {
        val path = System.getenv("MINIS_ZIP64_IOS_PACKAGE")
        Assume.assumeTrue("set MINIS_ZIP64_IOS_PACKAGE to run", path != null)
        val pkg = File(path!!)
        val dest = File(pkg.parentFile, "ios-extract").apply { deleteRecursively() }
        try {
            BackupZip.extract(pkg, dest)
            assertEquals(0x1_0000_0000L + 4096, File(dest, "a-big.bin").length())
            assertEquals("""{"format":"minisbak/1"}""", File(dest, "manifest.json").readText())
        } finally {
            dest.deleteRecursively()
        }
    }

    private fun tailOf(f: File): ByteArray = RandomAccessFile(f, "r").use { raf ->
        val n = minOf(raf.length(), 65_557L).toInt()
        raf.seek(raf.length() - n)
        ByteArray(n).also { raf.readFully(it) }
    }

    private fun ByteArray.indexOf(sig: ByteArray): Int =
        (0..size - sig.size).firstOrNull { i -> sig.indices.all { this[i + it] == sig[it] } } ?: -1

    private fun ByteArray.lastIndexOf(sig: ByteArray): Int =
        (size - sig.size downTo 0).firstOrNull { i -> sig.indices.all { this[i + it] == sig[it] } } ?: -1

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        val ZIP64_EOCD = byteArrayOf(0x50, 0x4B, 0x06, 0x06)
        val CLASSIC_EOCD = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    }

    @Test
    fun `refuses path traversal on extract`() {
        val evil = File(tmp, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            val payload = "pwned".toByteArray()
            val crc = CRC32().apply { update(payload) }
            val e = ZipEntry("../../escaped.txt").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(e); zos.write(payload); zos.closeEntry()
        }

        var threw = false
        try {
            BackupZip.extract(evil, File(tmp, "dest"))
        } catch (e: BackupZip.ZipException) {
            threw = true
        }
        assertTrue("a `..` entry must be refused", threw)
        assertTrue("nothing may be written outside the destination",
            !File(tmp, "escaped.txt").exists())
    }

    @Test
    fun `finds the package root whether or not iOS wrapped it`() {
        val flat = File(tmp, "flat").apply { mkdirs() }
        File(flat, "manifest.json").writeText("{}")
        assertEquals(flat, BackupZip.packageRoot(flat))

        val wrapped = File(tmp, "wrapped").apply { mkdirs() }
        val inner = File(wrapped, "minisbak-1234").apply { mkdirs() }
        File(inner, "manifest.json").writeText("{}")
        assertEquals(inner, BackupZip.packageRoot(wrapped))
    }

    @Test
    fun `reads a single entry by bare name even when iOS wrapped it`() {
        val wrapped = File(tmp, "w.zip")
        ZipOutputStream(wrapped.outputStream()).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            val payload = """{"format":"minisbak/1"}""".toByteArray()
            val crc = CRC32().apply { update(payload) }
            val e = ZipEntry("minisbak-abcd/manifest.json").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(e); zos.write(payload); zos.closeEntry()
        }
        val bytes = BackupZip.readEntry(wrapped, "manifest.json")
        assertEquals("""{"format":"minisbak/1"}""", bytes?.toString(Charsets.UTF_8))
    }
}
