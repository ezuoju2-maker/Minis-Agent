package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File

class BackupFileTreeExporter(
    private val blobStore: BackupBlobStore,
    private val fileIndex: BackupFileIndexWriter,
    private val snapshotAtMillis: Long = Long.MAX_VALUE,
    private val isCancelled: () -> Boolean = { false },
) {

    data class Result(
        var filesIncluded: Int = 0,
        var filesSkipped: Int = 0,
        var bytesIncluded: Long = 0,
        var directories: Int = 0,
        var filesAfterSnapshot: Int = 0,
    )

    fun export(
        root: File,
        logicalPrefix: String,
        category: BackupCategory,
        sessionId: String? = null,
    ): Result {
        val result = Result()
        if (!root.exists() || !root.isDirectory) return result

        val base = root.canonicalFile
        var scanned = 0
        for (entry in base.walkTopDown()) {
            if (++scanned % CANCEL_CHECK_EVERY == 0 && isCancelled()) {
                throw kotlinx.coroutines.CancellationException("Backup stopped")
            }
            if (entry == base) continue

            val rel = entry.relativeTo(base).path.replace(File.separatorChar, '/')
            if (rel.isEmpty()) continue
            val logicalPath = "$logicalPrefix/$rel"

            if (isBackupArtifact(rel)) continue

            if (entry.isDirectory) {
                if (entry.listFiles()?.isEmpty() == true) {
                    fileIndex.write(BackupFileIndexEntry.directory(logicalPath, category))
                    result.directories += 1
                }
                continue
            }
            if (!entry.isFile) continue

            val modified = entry.lastModified()
            if (modified > 0 && modified > snapshotAtMillis) {
                result.filesAfterSnapshot += 1
                continue
            }

            when (val outcome = addFile(entry, logicalPath, sessionId, category, result)) {
                is BackupBlobStore.Outcome.Stored -> {
                    fileIndex.write(
                        BackupFileIndexEntry.file(logicalPath, outcome.size, outcome.sha256, category)
                    )
                    result.filesIncluded += 1
                    result.bytesIncluded += outcome.size
                }
                is BackupBlobStore.Outcome.Duplicate -> {
                    fileIndex.write(
                        BackupFileIndexEntry.file(logicalPath, outcome.size, outcome.sha256, category)
                    )
                    result.filesIncluded += 1
                }
                is BackupBlobStore.Outcome.SkippedTooLarge -> {
                    fileIndex.write(
                        BackupFileIndexEntry.sizeSkipped(logicalPath, outcome.size, category)
                    )
                    result.filesSkipped += 1
                }
                null -> {
                    fileIndex.write(
                        BackupFileIndexEntry.unreadable(logicalPath, entry.length(), category)
                    )
                    result.filesSkipped += 1
                }
            }
        }
        return result
    }

    private fun addFile(
        file: File,
        logicalPath: String,
        sessionId: String?,
        category: BackupCategory,
        result: Result,
    ): BackupBlobStore.Outcome? = try {
        blobStore.addFile(file, logicalPath, sessionId)
    } catch (e: Exception) {
        AppLogger.error(TAG, "[Backup] unreadable file $logicalPath: ${e.message}")
        null
    }

    private fun isBackupArtifact(rel: String): Boolean {
        if (rel.endsWith(".${BackupFormat.FILE_EXTENSION}")) return true
        return rel.substringBefore('/') == BACKUPS_DIR_NAME
    }

    companion object {
        internal const val CANCEL_CHECK_EVERY = 32

        private const val TAG = "Backup"

        const val BACKUPS_DIR_NAME = "Backups"
    }
}

class BackupFileIndexWriter(file: File) : AutoCloseable {
    private val stream = file.also { it.parentFile?.mkdirs() }.outputStream()

    var written = 0
        private set

    fun write(entry: BackupFileIndexEntry) {
        val line = BackupFormat.json.encodeToString(BackupFileIndexEntry.serializer(), entry) + "\n"
        stream.write(line.toByteArray(Charsets.UTF_8))
        stream.flush()
        written += 1
    }

    override fun close() {
        stream.flush()
        stream.close()
    }
}
