package com.openminis.app.sandbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class RootfsManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var manager: RootfsManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        resetSingleton()
        manager = RootfsManager.getInstance(context)

        manager.rootfsDir.deleteRecursively()
        manager.prootBinary.parentFile?.deleteRecursively()
    }

    @After
    fun tearDown() {
        manager.rootfsDir.deleteRecursively()
        manager.prootBinary.parentFile?.deleteRecursively()
        resetSingleton()
    }


    @Test
    fun isNotInstalledWhenDirDoesNotExist() {
        assertFalse(manager.isInstalled)
    }

    @Test
    fun isNotInstalledWhenArchFileMissing() {
        manager.rootfsDir.mkdirs()
        assertFalse(manager.isInstalled)
    }

    @Test
    fun isNotInstalledWhenArchMismatch() {
        manager.rootfsDir.mkdirs()
        File(manager.rootfsDir, ".arch").writeText("x86_64")
        assertFalse(manager.isInstalled)
    }

    @Test
    fun isInstalledWhenRootfsAndArchPresent() {
        manager.rootfsDir.mkdirs()
        File(manager.rootfsDir, ".arch").writeText("aarch64")
        assertTrue(manager.isInstalled)
    }


    @Test
    fun installIfNeededExtractsRootfsFromAssets() = runBlocking {
        if (!hasAsset("alpine-minirootfs.tar.gz")) {
            println("SKIP: alpine-minirootfs.tar.gz not in assets")
            return@runBlocking
        }

        manager.installIfNeeded()

        assertTrue("rootfsDir should exist", manager.rootfsDir.exists())
        assertTrue("Should be marked installed", manager.isInstalled)

        val archFile = File(manager.rootfsDir, ".arch")
        assertEquals("aarch64", archFile.readText().trim())

        val expectedDirs = listOf("attachments", "offloads", "workspace", "skills", "memory")
        for (subdir in expectedDirs) {
            val dir = File(manager.rootfsDir, "var/minis/$subdir")
            assertTrue("var/minis/$subdir should exist", dir.exists())
            assertTrue("var/minis/$subdir should be directory", dir.isDirectory)
        }

        val resolv = File(manager.rootfsDir, "etc/resolv.conf")
        assertTrue("resolv.conf should exist", resolv.exists())
        val content = resolv.readText()
        assertTrue("Should contain 8.8.8.8", content.contains("8.8.8.8"))
        assertTrue("Should contain 8.8.4.4", content.contains("8.8.4.4"))
    }

    @Test
    fun installIfNeededIsIdempotent() = runBlocking {
        if (!hasAsset("alpine-minirootfs.tar.gz")) {
            println("SKIP: alpine-minirootfs.tar.gz not in assets")
            return@runBlocking
        }

        manager.installIfNeeded()
        val firstModTime = File(manager.rootfsDir, ".arch").lastModified()

        Thread.sleep(50)

        manager.installIfNeeded()
        val secondModTime = File(manager.rootfsDir, ".arch").lastModified()

        assertEquals("Should not re-extract if already installed", firstModTime, secondModTime)
    }

    @Test
    fun installIfNeededCleansPartialInstall() = runBlocking {
        if (!hasAsset("alpine-minirootfs.tar.gz")) {
            println("SKIP: alpine-minirootfs.tar.gz not in assets")
            return@runBlocking
        }

        manager.rootfsDir.mkdirs()
        File(manager.rootfsDir, "stale-file.txt").writeText("stale")

        manager.installIfNeeded()

        assertTrue(manager.isInstalled)
        assertFalse("Stale file should be cleaned", File(manager.rootfsDir, "stale-file.txt").exists())
    }


    @Test
    fun installProotIfNeededExtractsBinary() = runBlocking {
        if (!hasAsset("proot-aarch64")) {
            println("SKIP: proot-aarch64 not in assets")
            return@runBlocking
        }

        manager.installProotIfNeeded()

        assertTrue("PRoot binary should exist", manager.prootBinary.exists())
        assertTrue("PRoot binary should be executable", manager.prootBinary.canExecute())
        assertTrue("PRoot binary should not be empty", manager.prootBinary.length() > 0)
    }

    @Test
    fun installProotIfNeededIsIdempotent() = runBlocking {
        if (!hasAsset("proot-aarch64")) {
            println("SKIP: proot-aarch64 not in assets")
            return@runBlocking
        }

        manager.installProotIfNeeded()
        val firstSize = manager.prootBinary.length()

        manager.installProotIfNeeded()
        val secondSize = manager.prootBinary.length()

        assertEquals("Should not re-extract", firstSize, secondSize)
    }


    @Test
    fun ensureSessionDirsCreatesAllSubdirs() {
        val sessionId = "test-session-${System.nanoTime()}"
        manager.ensureSessionDirs(sessionId)

        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        assertTrue(sessionBase.exists())

        val expectedSubdirs = listOf("attachments", "offloads", "workspace", "browser")
        for (subdir in expectedSubdirs) {
            val dir = File(sessionBase, subdir)
            assertTrue("$subdir should exist", dir.exists())
            assertTrue("$subdir should be directory", dir.isDirectory)
        }

        sessionBase.deleteRecursively()
    }

    @Test
    fun ensureSessionDirsIsIdempotent() {
        val sessionId = "idempotent-session"
        manager.ensureSessionDirs(sessionId)
        manager.ensureSessionDirs(sessionId)

        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        assertTrue(sessionBase.exists())
        sessionBase.deleteRecursively()
    }


    @Test
    fun resetDeletesAndReinstalls() = runBlocking {
        if (!hasAsset("alpine-minirootfs.tar.gz")) {
            println("SKIP: alpine-minirootfs.tar.gz not in assets")
            return@runBlocking
        }

        manager.installIfNeeded()

        File(manager.rootfsDir, "custom.txt").writeText("custom")
        assertTrue(File(manager.rootfsDir, "custom.txt").exists())

        manager.reset(keepUserData = false)

        assertTrue("Should be reinstalled", manager.isInstalled)
        assertFalse("Custom file should be gone", File(manager.rootfsDir, "custom.txt").exists())
    }

    @Test
    fun resetKeepsUserDataWhenRequested() = runBlocking {
        if (!hasAsset("alpine-minirootfs.tar.gz")) {
            println("SKIP: alpine-minirootfs.tar.gz not in assets")
            return@runBlocking
        }

        manager.installIfNeeded()

        val rootHome = File(manager.rootfsDir, "root")
        rootHome.mkdirs()
        File(rootHome, "userfile.txt").writeText("user data")

        manager.reset(keepUserData = true)

        assertTrue("Should be reinstalled", manager.isInstalled)
        val restored = File(manager.rootfsDir, "root/userfile.txt")
        assertTrue("User file should be restored", restored.exists())
        assertEquals("user data", restored.readText())
    }


    @Test
    fun extractTarHandlesSyntheticTarGz() = runBlocking {
        val tarBytes = buildSimpleTar(
            "hello.txt" to "Hello from tar!",
            "subdir/" to null,
            "subdir/nested.txt" to "Nested content"
        )
        val gzBytes = gzip(tarBytes)

        val testDir = File(context.cacheDir, "tar-extract-test-${System.nanoTime()}")
        try {
            testDir.mkdirs()
            java.util.zip.GZIPInputStream(ByteArrayInputStream(gzBytes)).use { gzStream ->
                manager.extractTar(gzStream, testDir)
            }

            assertTrue(File(testDir, "hello.txt").exists())
            assertEquals("Hello from tar!", File(testDir, "hello.txt").readText())
            assertTrue(File(testDir, "subdir").isDirectory)
            assertEquals("Nested content", File(testDir, "subdir/nested.txt").readText())
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun extractTarHandlesEmptyArchive() {
        val emptyTar = ByteArray(1024)
        val testDir = File(context.cacheDir, "empty-tar-test-${System.nanoTime()}")
        try {
            testDir.mkdirs()
            manager.extractTar(ByteArrayInputStream(emptyTar), testDir)

            assertEquals(0, testDir.listFiles()?.size ?: 0)
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun extractTarHandlesLargeFileContent() {
        val largeContent = "A".repeat(10_000)
        val tarBytes = buildSimpleTar("large.txt" to largeContent)

        val testDir = File(context.cacheDir, "large-tar-test-${System.nanoTime()}")
        try {
            testDir.mkdirs()
            manager.extractTar(ByteArrayInputStream(tarBytes), testDir)

            assertEquals(largeContent, File(testDir, "large.txt").readText())
        } finally {
            testDir.deleteRecursively()
        }
    }


    @Test
    fun singletonReturnsSameInstance() {
        val a = RootfsManager.getInstance(context)
        val b = RootfsManager.getInstance(context)
        assertSame(a, b)
    }


    private fun hasAsset(name: String): Boolean {
        return try {
            context.assets.open(name).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun resetSingleton() {
        try {
            val field = RootfsManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) { }
    }

    private fun buildSimpleTar(vararg entries: Pair<String, String?>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val isDir = content == null
            val data = content?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val header = ByteArray(512)

            name.toByteArray().copyInto(header, 0, 0, minOf(name.length, 100))

            val mode = if (isDir) "0000755" else "0000644"
            mode.toByteArray().copyInto(header, 100)

            "0000000".toByteArray().copyInto(header, 108)
            "0000000".toByteArray().copyInto(header, 116)

            String.format("%011o", data.size.toLong()).toByteArray().copyInto(header, 124)

            "00000000000".toByteArray().copyInto(header, 136)

            header[156] = if (isDir) '5'.code.toByte() else '0'.code.toByte()

            "ustar".toByteArray().copyInto(header, 257)
            header[263] = '0'.code.toByte()
            header[264] = '0'.code.toByte()

            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var checksum = 0
            for (b in header) checksum += (b.toInt() and 0xFF)
            String.format("%06o\u0000 ", checksum).toByteArray().copyInto(header, 148)

            out.write(header)
            if (data.isNotEmpty()) {
                out.write(data)
                val rem = data.size % 512
                if (rem != 0) out.write(ByteArray(512 - rem))
            }
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
}
