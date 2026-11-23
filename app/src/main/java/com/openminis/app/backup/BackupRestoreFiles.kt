package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.UUID

object BackupRestoreFiles {

    private const val TAG = "Restore"

    data class Result(
        var written: Int = 0,
        var bytes: Long = 0,
        var missingBlobs: Int = 0,
        var sizeSkippedInPackage: Int = 0,
        var notDownloadedInPackage: Int = 0,
        var rejectedPaths: Int = 0,
    )

    fun restore(
        packageRoot: File,
        fileIndex: List<BackupFileIndexEntry>,
        category: BackupCategory,
        containmentRoot: File,
        destinationFor: (String) -> File?,
    ): Result {
        val result = Result()
        val root = containmentRoot.canonicalFile

        for (entry in fileIndex) {
            if (entry.category != category.key) continue

            if (entry.skipped != null) {
                when (entry.skipped) {
                    "size" -> result.sizeSkippedInPackage += 1
                    "not_downloaded" -> result.notDownloadedInPackage += 1
                    else -> result.sizeSkippedInPackage += 1
                }
                continue
            }

            val dest = destinationFor(entry.path) ?: continue

            if (!isContained(dest, root)) {
                AppLogger.error(TAG, "[Restore] REJECTED path escaping its category root: ${entry.path}")
                result.rejectedPaths += 1
                continue
            }

            if (entry.isDirectory == true) {
                dest.mkdirs()
                continue
            }
            val sha = entry.sha256 ?: continue
            val blob = File(File(File(packageRoot, "blobs"), sha.take(2)), sha)
            if (!blob.isFile) {
                AppLogger.info(
                    TAG,
                    "[Restore] index references a blob not in the package: ${entry.path} sha=${sha.take(12)}"
                )
                result.missingBlobs += 1
                continue
            }

            dest.parentFile?.mkdirs()
            if (writeViaStagedSwap(blob, dest, entry.path)) {
                result.written += 1
                result.bytes += entry.size
            }
        }
        return result
    }

    private fun writeViaStagedSwap(blob: File, dest: File, logicalPath: String): Boolean {
        val staged = File(dest.parentFile, ".restore-${UUID.randomUUID()}.tmp")
        return try {
            blob.copyTo(staged, overwrite = true)
            if (!staged.renameTo(dest)) {
                if (dest.exists() && !dest.delete()) throw BackupException("cannot replace $logicalPath")
                if (!staged.renameTo(dest)) throw BackupException("cannot finalise $logicalPath")
            }
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Restore] failed to write $logicalPath: ${e.message}")
            staged.delete()
            false
        }
    }

    private fun isContained(candidate: File, root: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val target = if (candidate.exists()) {
            candidate.canonicalFile
        } else {
            File(candidate.parentFile?.canonicalFile ?: return false, candidate.name)
        }
        val path = target.path
        path == rootPath || path.startsWith(rootPath + File.separator)
    }.getOrDefault(false)
}
