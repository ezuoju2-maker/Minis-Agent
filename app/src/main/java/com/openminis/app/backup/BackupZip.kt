package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupZip {

    private const val TAG = "Backup"

    private const val COPY_BUFFER_BYTES = 256 * 1024

    private const val PROBE_BYTES = 64 * 1024

    private const val MAX_DEFLATE_BYTES = 32L * 1024 * 1024

    private val INCOMPRESSIBLE_MAGIC: List<ByteArray> = listOf(
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        byteArrayOf(0x47, 0x49, 0x46, 0x38),
        byteArrayOf(0x52, 0x49, 0x46, 0x46),
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x25, 0x50, 0x44, 0x46),
        byteArrayOf(0x49, 0x44, 0x33),
        byteArrayOf(0xFF.toByte(), 0xFB.toByte()),
        byteArrayOf(0x4F, 0x67, 0x67, 0x53),
        byteArrayOf(0x66, 0x4C, 0x61, 0x43),
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
        byteArrayOf(0x42, 0x5A, 0x68),
        byteArrayOf(0x1F, 0x8B.toByte()),
        byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A),
        byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C),
        byteArrayOf(0x52, 0x61, 0x72, 0x21),
    )

    class ZipException(message: String) : Exception(message)


    fun archive(staging: File, destination: File) {
        val base = staging.canonicalFile
        val members = base.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(base).invariantPath() }
            .toList()

        ZipOutputStream(destination.outputStream().buffered()).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            val buf = ByteArray(COPY_BUFFER_BYTES)
            members.forEach { file ->
                writeEntry(zos, file.relativeTo(base).invariantPath(), file, buf)
            }
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, file: File, buf: ByteArray) {
        val size = file.length()
        val deflatable = size <= MAX_DEFLATE_BYTES && !shouldStore(file, buf)
        if (deflatable) writeDeflatedEntry(zos, name, file, buf)
        else writeStoredEntry(zos, name, file, size, buf)
    }

    internal fun shouldStore(file: File, buf: ByteArray): Boolean {
        val head = ByteArray(minOf(PROBE_BYTES.toLong(), file.length()).toInt())
        if (head.isEmpty()) return true
        file.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read < head.size) return shouldStoreBytes(head.copyOf(read))
        }
        return shouldStoreBytes(head)
    }

    internal fun shouldStoreBytes(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        for (magic in INCOMPRESSIBLE_MAGIC) {
            if (head.size >= magic.size && magic.indices.all { head[it] == magic[it] }) return true
        }
        if (head.size >= 8 && head[4] == 0x66.toByte() && head[5] == 0x74.toByte() &&
            head[6] == 0x79.toByte() && head[7] == 0x70.toByte()
        ) {
            return true
        }
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(head)
            deflater.finish()
            val out = ByteArray(head.size + 64)
            var total = 0
            while (!deflater.finished() && total < out.size) {
                val n = deflater.deflate(out, total, out.size - total)
                if (n == 0) break
                total += n
            }
            if (!deflater.finished()) return true
            return total > (head.size * 95) / 100
        } finally {
            deflater.end()
        }
    }

    private fun writeStoredEntry(
        zos: ZipOutputStream,
        name: String,
        file: File,
        size: Long,
        buf: ByteArray,
    ) {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc.value
            time = file.lastModified()
        }
        zos.putNextEntry(entry)
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }

    private fun writeDeflatedEntry(zos: ZipOutputStream, name: String, file: File, buf: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = file.lastModified()
        }
        zos.putNextEntry(entry)
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }


    fun extract(zipFile: File, destination: File, onProgress: ((String) -> Unit)? = null) {
        destination.mkdirs()
        val root = destination.canonicalFile
        val buf = ByteArray(COPY_BUFFER_BYTES)
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = safeResolve(root, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { sink ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                    onProgress?.invoke(entry.name)
                }
                zis.closeEntry()
            }
        }
    }

    fun readEntry(zipFile: File, name: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray? {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == name || entry.name.endsWith("/$name")) {
                    return zis.readAtMost(maxBytes)
                }
                zis.closeEntry()
            }
        }
        return null
    }

    fun listEntries(zipFile: File): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                out.add(entry.name)
                zis.closeEntry()
            }
        }
        return out
    }

    fun packageRoot(extracted: File): File {
        if (File(extracted, "manifest.json").exists()) return extracted
        val dirs = extracted.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val wrapped = dirs.firstOrNull { File(it, "manifest.json").exists() }
        if (wrapped != null) {
            AppLogger.info(TAG, "[Backup] package root is wrapped in '${wrapped.name}'")
            return wrapped
        }
        return extracted
    }

    private fun safeResolve(root: File, entryName: String): File {
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        val parts = entryName.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        val resolved = parts.fold(root) { acc, part -> File(acc, part) }
        if (!resolved.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            resolved.canonicalPath != root.canonicalPath
        ) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        return resolved
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private fun InputStream.readAtMost(max: Int): ByteArray {
        val buf = ByteArray(minOf(max, 64 * 1024))
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
