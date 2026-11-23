package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class BackupBlobStore(stagingRoot: File, private val maxFileBytes: Long?) {

    private val blobsRoot = File(stagingRoot, "blobs")

    val maxFileBytesForManifest: Long? get() = maxFileBytes

    private val seen = mutableSetOf<String>()

    private val _blobIndex = mutableListOf<BackupBlobIndexEntry>()
    val blobIndex: List<BackupBlobIndexEntry> get() = _blobIndex

    var totalBytesStored = 0L
        private set
    var skippedFiles = 0
        private set
    var skippedBytes = 0L
        private set

    val skippedPaths = mutableListOf<SkippedPath>()

    data class SkippedPath(val path: String, val size: Long)

    sealed interface Outcome {
        data class Stored(val sha256: String, val size: Long) : Outcome
        data class Duplicate(val sha256: String, val size: Long) : Outcome
        data class SkippedTooLarge(val size: Long) : Outcome
    }

    fun rehydrateFromFileIndex(fileIndex: File) {
        if (!fileIndex.exists()) return
        var restored = 0
        fileIndex.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val entry = runCatching {
                    BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), line)
                }.getOrNull() ?: continue
                val sha = entry.sha256 ?: continue
                if (entry.skipped != null || entry.isDirectory == true) continue
                if (!seen.add(sha)) continue
                val blob = blobFile(sha)
                if (!blob.exists()) {
                    seen.remove(sha)
                    continue
                }
                totalBytesStored += entry.size
                _blobIndex.add(
                    BackupBlobIndexEntry(sha, entry.size, entry.path, null, mimeType(entry.path))
                )
                restored += 1
            }
        }
        if (restored > 0) {
            AppLogger.info(TAG, "[Backup] resume: recovered $restored blob(s) from a previous attempt")
        }
    }

    fun addFile(file: File, logicalPath: String, sessionId: String? = null): Outcome {
        val size = file.length()

        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size))
            AppLogger.info(TAG, "[Backup] skip (size cap) $logicalPath size=$size > $maxFileBytes")
            return Outcome.SkippedTooLarge(size)
        }

        val digest = file.inputStream().use { sha256(it) }
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        file.copyTo(dest, overwrite = true)

        seen.add(digest)
        totalBytesStored += size
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    fun addBytes(data: ByteArray, logicalPath: String, sessionId: String? = null): Outcome {
        val size = data.size.toLong()
        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size))
            return Outcome.SkippedTooLarge(size)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(data).toHex()
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        dest.writeBytes(data)

        seen.add(digest)
        totalBytesStored += size
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    private fun blobFile(digest: String) = File(File(blobsRoot, digest.take(2)), digest)

    companion object {
        private const val TAG = "Backup"

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().toHex()
        }

        fun sha256OfFile(file: File): String = file.inputStream().buffered().use { sha256(it) }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        fun mimeType(path: String): String? =
            when (path.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "pdf" -> "application/pdf"
                "txt", "log" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "zip" -> "application/zip"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> null
            }
    }
}
