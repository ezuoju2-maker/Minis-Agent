package com.openminis.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DirectorySizeSymlinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) {
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    @Test
    fun `a symlink to a file is not counted as a second copy`() {
        val root = tmp.newFolder("root")
        write(File(root, "real/big.bin"), 1_000)
        Files.createSymbolicLink(
            File(root, "link.bin").toPath(),
            File(root, "real/big.bin").toPath(),
        )
        assertEquals(1_000L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a symlink to a directory is not descended into`() {
        val root = tmp.newFolder("root")
        write(File(root, "usr/lib/a.so"), 4_000)
        write(File(root, "usr/lib/b.so"), 6_000)
        Files.createSymbolicLink(
            File(root, "lib").toPath(),
            File(root, "usr/lib").toPath(),
        )
        assertEquals(10_000L, SessionStorage.directorySize(root))
    }

    @Test
    fun `many links to one target inflate nothing`() {
        val root = tmp.newFolder("root")
        write(File(root, "bin/busybox"), 900_000)
        repeat(300) { i ->
            Files.createSymbolicLink(
                File(root, "bin/applet$i").toPath(),
                File(root, "bin/busybox").toPath(),
            )
        }
        assertEquals(900_000L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a symlink pointing outside the tree cannot import foreign bytes`() {
        val outside = tmp.newFolder("outside")
        write(File(outside, "huge.bin"), 50_000)
        val root = tmp.newFolder("root")
        write(File(root, "own.bin"), 7)
        Files.createSymbolicLink(
            File(root, "escape").toPath(),
            outside.toPath(),
        )
        assertEquals(7L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a symlink cycle terminates instead of hanging`() {
        val root = tmp.newFolder("root")
        write(File(root, "a/file.bin"), 42)
        Files.createSymbolicLink(
            File(root, "a/loop").toPath(),
            File(root, "a").toPath(),
        )
        assertEquals(42L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a broken symlink is skipped rather than throwing`() {
        val root = tmp.newFolder("root")
        write(File(root, "real.bin"), 11)
        Files.createSymbolicLink(
            File(root, "dangling").toPath(),
            File(root, "does-not-exist").toPath(),
        )
        assertEquals(11L, SessionStorage.directorySize(root))
    }

    @Test
    fun `ordinary nested files are still summed`() {
        val root = tmp.newFolder("root")
        write(File(root, "a.bin"), 100)
        write(File(root, "x/b.bin"), 200)
        write(File(root, "x/y/c.bin"), 300)
        assertEquals(600L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a missing directory is zero`() {
        assertEquals(0L, SessionStorage.directorySize(File(tmp.root, "nope")))
    }

    @Test
    fun `walkTopDown follows directory symlinks`() {
        val root = tmp.newFolder("root")
        write(File(root, "usr/lib/a.so"), 4_000)
        Files.createSymbolicLink(
            File(root, "lib").toPath(),
            File(root, "usr/lib").toPath(),
        )

        var naive = 0L
        root.walkTopDown().forEach { if (it.isFile) naive += it.length() }

        assertEquals("the naive walk sees the payload twice", 8_000L, naive)
        assertTrue(
            "and that is strictly more than the truth",
            naive > SessionStorage.directorySize(root),
        )
    }


    private fun mainSources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no file rolls its own recursive directory-size walk`() {
        val bytesWrittenNotBytesOnDisk = setOf(
            "backup/BackupZip.kt",
            "backup/BackupExporter.kt",
            "backup/BackupImporter.kt",
        )

        val offenders = mutableListOf<String>()
        for (f in mainSources()) {
            if (f.path.endsWith("SessionStorage.kt")) continue
            val norm = f.path.replace(File.separatorChar, '/')
            if (bytesWrittenNotBytesOnDisk.any { norm.endsWith(it) }) continue
            val text = f.readText()
            val sumsLength = Regex("""\+=[^\n]*\.length\(\)|sumOf\s*\{[^}]*\.length\(\)""")
                .containsMatchIn(text)
            if (!sumsLength) continue
            val walksTree = Regex("""walkTopDown\(\)""").containsMatchIn(text) ||
                Regex("""isDirectory\s*\)\s*\{?\s*
?\s*\w*[Dd]irSize|calculateDirSize\(""")
                    .containsMatchIn(text)
            if (walksTree) offenders.add(f.path)
        }
        assertTrue(
            "these files sum file lengths over a tree walk of their own instead of " +
                "calling SessionStorage.directorySize — every hand-rolled copy so far has " +
                "followed symlinks and over-reported: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the known former offenders now delegate to the shared implementation`() {
        for (rel in listOf(
            "src/main/java/com/openminis/app/sandbox/RootfsManager.kt",
            "src/main/java/com/openminis/app/sandbox/offload/DeviceOffloadHandler.kt",
            "src/main/java/com/openminis/app/debug/DebugRPCHandler.kt",
        )) {
            val f = File(rel)
            assertTrue("$rel not found (cwd=${File(".").absolutePath})", f.isFile)
            assertTrue(
                "$rel no longer routes its size walk through SessionStorage.directorySize",
                f.readText().contains("SessionStorage.directorySize"),
            )
        }
    }
}
