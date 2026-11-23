package com.openminis.app.backup.remote

import android.content.Context
import com.openminis.app.backup.BackupFormat
import com.openminis.app.logging.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RcloneChunkedUpload(private val context: Context) {

    data class Progress(
        val bytesSent: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double = 0.0,
    ) {
        val fraction: Double get() = if (totalBytes > 0) bytesSent.toDouble() / totalBytes else 0.0

        val secondsRemaining: Long?
            get() {
                if (bytesPerSecond <= 0 || totalBytes <= 0) return null
                val left = totalBytes - bytesSent
                if (left <= 0) return 0
                return (left / bytesPerSecond).toLong()
            }
    }

    class CancelFlag {
        @Volatile
        private var cancelled = false
        fun cancel() { cancelled = true }
        fun isCancelled(): Boolean = cancelled
    }

    class UploadException(message: String) : Exception(message)
    class CancelledException : Exception("Upload cancelled.")


    fun upload(
        packageFile: File,
        remote: RcloneRemoteStore.Remote,
        backupId: String = "",
        isCancelled: () -> Boolean = { false },
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        if (!packageFile.exists()) throw UploadException("Couldn't read the backup file.")
        val size = packageFile.length()
        val name = packageFile.name
        val fs = remote.fsSpec

        val partial = remote.join("$name.$PARTIAL_SUFFIX")
        val final = remote.join(name)

        sweepAbandonedPartials(remote, keeping = partial)

        var lastError: Exception? = null
        for (attempt in 1..2) {
            if (isCancelled()) throw CancelledException()
            if (attempt > 1) pauseCancellably(RETRY_BACKOFF_MS, isCancelled)
            try {
                runCopyJob(
                    srcFs = packageFile.parentFile?.absolutePath ?: "",
                    srcRemote = name,
                    dstFs = fs,
                    dstRemote = partial,
                    isCancelled = isCancelled,
                ) { moved -> onProgress?.invoke(Progress(minOf(moved, size), size)) }

                val uploaded = remoteSize(fs, partial)
                if (uploaded != size) {
                    throw UploadException(
                        "Upload verification failed: the server has $uploaded bytes " +
                            "but the backup is $size bytes."
                    )
                }
                RcloneBridge.rpc(
                    "operations/movefile",
                    mapOf(
                        "srcFs" to fs, "srcRemote" to partial,
                        "dstFs" to fs, "dstRemote" to final,
                    ),
                )
                onProgress?.invoke(Progress(size, size))
                AppLogger.info(
                    TAG,
                    "[Rclone] uploaded $name ($size bytes, verified) -> ${remote.name}, attempt $attempt"
                )
                return
            } catch (e: Exception) {
                lastError = e
                runCatching {
                    RcloneBridge.rpc("operations/deletefile", mapOf("fs" to fs, "remote" to partial))
                }
                if (e is CancelledException || isCancelled()) {
                    AppLogger.info(TAG, "[Rclone] upload to '${remote.name}' stopped on attempt $attempt")
                    throw CancelledException()
                }
                AppLogger.warning(
                    TAG,
                    "[Rclone] upload attempt $attempt to '${remote.name}' failed: ${e.message}"
                )
            }
        }
        throw UploadException(lastError?.message ?: "The remote rejected the upload.")
    }

    private fun runCopyJob(
        srcFs: String,
        srcRemote: String,
        dstFs: String,
        dstRemote: String,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ) {
        val args = mapOf("srcFs" to srcFs, "srcRemote" to srcRemote, "dstFs" to dstFs, "dstRemote" to dstRemote)
        val group = "backup-${java.util.UUID.randomUUID()}"
        val jobId = RcloneBridge.rpc("operations/copyfile", args + mapOf("_async" to true, "_group" to group))
            .optInt("jobid", -1)
        if (jobId < 0) {
            AppLogger.error(TAG, "[Rclone] copyfile returned no jobid; falling back to a blocking upload")
            RcloneBridge.rpc("operations/copyfile", args)
            return
        }
        var stopSent = false
        var statusMisses = 0
        while (true) {
            Thread.sleep(POLL_INTERVAL_MS)
            if (!stopSent && isCancelled()) {
                stopSent = true
                AppLogger.info(TAG, "[Rclone] stop requested; stopping upload job $jobId")
                runCatching { RcloneBridge.rpc("job/stop", mapOf("jobid" to jobId)) }
            }
            val status = runCatching { RcloneBridge.rpc("job/status", mapOf("jobid" to jobId)) }.getOrNull()
            if (status == null) {
                if (++statusMisses >= MAX_STATUS_MISSES) throw UploadException("Lost track of the upload.")
                continue
            }
            statusMisses = 0
            if (!status.optBoolean("finished")) {
                val stats = runCatching { RcloneBridge.rpc("core/stats", mapOf("group" to group)) }.getOrNull()
                onBytes((stats?.optLong("bytes") ?: 0L).coerceAtLeast(0L))
                continue
            }
            if (stopSent) throw CancelledException()
            val error = status.optString("error").orEmpty()
            if (error.isNotEmpty()) throw UploadException(error)
            return
        }
    }

    private fun pauseCancellably(ms: Long, isCancelled: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (isCancelled()) throw CancelledException()
            Thread.sleep(minOf(100L, until - System.currentTimeMillis()).coerceAtLeast(1L))
        }
        if (isCancelled()) throw CancelledException()
    }

    private fun sweepAbandonedPartials(remote: RcloneRemoteStore.Remote, keeping: String) {
        val fs = remote.fsSpec
        val list = runCatching {
            RcloneBridge.rpc("operations/list", mapOf("fs" to fs, "remote" to remote.listRoot))
                .optJSONArray("list")
        }.getOrNull() ?: return

        var removed = 0
        var bytes = 0L
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            if (e.optBoolean("IsDir")) continue
            val name = e.optString("Name")
            if (!name.endsWith(".$PARTIAL_SUFFIX")) continue
            val path = remote.join(name)
            if (path == keeping) continue
            bytes += e.optLong("Size")
            runCatching {
                RcloneBridge.rpc("operations/deletefile", mapOf("fs" to fs, "remote" to path))
            }.onSuccess { removed++ }
        }
        if (removed > 0) {
            AppLogger.info(
                TAG,
                "[Rclone] swept $removed abandoned .$PARTIAL_SUFFIX upload(s) from " +
                    "'${remote.name}', freeing $bytes bytes"
            )
        }
    }

    fun deletePackage(remote: RcloneRemoteStore.Remote, packageName: String) {
        RcloneBridge.rpc(
            "operations/deletefile",
            mapOf("fs" to remote.fsSpec, "remote" to remote.join(packageName)),
        )
        AppLogger.info(TAG, "[Rclone] deleted $packageName from ${remote.name}")
    }

    fun deletePackage(remote: RcloneRemoteStore.Remote, pkg: RemotePackage) {
        if (!pkg.isChunked) {
            RcloneBridge.rpc("operations/deletefile", mapOf("fs" to remote.fsSpec, "remote" to pkg.key))
            AppLogger.info(TAG, "[Rclone] deleted ${pkg.key} from ${remote.name}")
            return
        }
        RcloneBridge.rpc("operations/purge", mapOf("fs" to remote.fsSpec, "remote" to pkg.key))
        AppLogger.info(
            TAG,
            "[Rclone] purged legacy parts dir ${pkg.key} (${pkg.partCount} parts) from ${remote.name}",
        )
    }

    private fun remoteSize(fs: String, path: String): Long {
        val item = RcloneBridge.rpc("operations/stat", mapOf("fs" to fs, "remote" to path))
            .optJSONObject("item")
            ?: throw UploadException("Uploaded file not found on the server.")
        return item.optLong("Size", -1L)
    }


    data class RemotePackage(
        val key: String,
        val displayName: String,
        val size: Long,
        val modified: Long?,
        val partCount: Int,
    ) {
        val isChunked: Boolean get() = partCount > 1
    }

    data class RemoteEntry(
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long?,
    )

    fun listDirectory(remote: RcloneRemoteStore.Remote, path: String): List<RemoteEntry> {
        val cleaned = path.trim('/')
        val listing = RcloneBridge.rpc(
            "operations/list", mapOf("fs" to remote.fsSpec, "remote" to cleaned),
        ).optJSONArray("list")
        val dirs = mutableListOf<RemoteEntry>()
        val files = mutableListOf<RemoteEntry>()
        for (i in 0 until (listing?.length() ?: 0)) {
            val e = listing?.optJSONObject(i) ?: continue
            val name = e.optString("Name")
            if (name.isEmpty() || name.startsWith(".")) continue
            val isDir = e.optBoolean("IsDir")
            val entry = RemoteEntry(
                path = if (cleaned.isEmpty()) name else "$cleaned/$name",
                name = name,
                isDirectory = isDir,
                size = e.optLong("Size"),
                modified = parseTime(e.optString("ModTime")),
            )
            if (isDir) dirs.add(entry)
            else if (name.endsWith(".${BackupFormat.FILE_EXTENSION}")) files.add(entry)
        }
        return dirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name } ) +
            files.sortedByDescending { it.modified ?: 0L }
    }

    fun listPackages(remote: RcloneRemoteStore.Remote): List<RemotePackage> {
        val found = mutableListOf<RemotePackage>()

        val root = RcloneBridge.rpc(
            "operations/list", mapOf("fs" to remote.fsSpec, "remote" to remote.listRoot)
        ).optJSONArray("list")
        for (i in 0 until (root?.length() ?: 0)) {
            val e = root?.optJSONObject(i) ?: continue
            val name = e.optString("Name")
            if (e.optBoolean("IsDir") || !name.endsWith(".${BackupFormat.FILE_EXTENSION}")) continue
            found.add(
                RemotePackage(
                    key = remote.join(name),
                    displayName = name,
                    size = e.optLong("Size"),
                    modified = parseTime(e.optString("ModTime")),
                    partCount = 1,
                )
            )
        }

        val partsRoot = remote.join(PARTS_DIR)
        val dirs = runCatching {
            RcloneBridge.rpc("operations/list", mapOf("fs" to remote.fsSpec, "remote" to partsRoot))
                .optJSONArray("list")
        }.getOrNull()
        for (i in 0 until (dirs?.length() ?: 0)) {
            val d = dirs?.optJSONObject(i) ?: continue
            if (!d.optBoolean("IsDir")) continue
            val backupId = d.optString("Name")
            val dir = "$partsRoot/$backupId"
            val parts = runCatching {
                RcloneBridge.rpc("operations/list", mapOf("fs" to remote.fsSpec, "remote" to dir))
                    .optJSONArray("list")
            }.getOrNull() ?: continue
            if (parts.length() == 0) continue
            var total = 0L
            for (j in 0 until parts.length()) total += parts.optJSONObject(j)?.optLong("Size") ?: 0
            found.add(
                RemotePackage(
                    key = dir,
                    displayName = backupId,
                    size = total,
                    modified = parseTime(parts.optJSONObject(0)?.optString("ModTime")),
                    partCount = parts.length(),
                )
            )
        }
        return found.sortedByDescending { it.modified ?: 0 }
    }

    private fun downloadWholeFile(
        pkg: RemotePackage,
        remote: RcloneRemoteStore.Remote,
        destination: File,
        cancelFlag: CancelFlag?,
        onProgress: ((Progress) -> Unit)?,
    ) {
        val fs = remote.fsSpec
        val dstFs = destination.parentFile?.absolutePath ?: ""
        val group = "restore-${java.util.UUID.randomUUID()}"
        val started = RcloneBridge.rpc(
            "operations/copyfile",
            mapOf(
                "srcFs" to fs, "srcRemote" to pkg.key,
                "dstFs" to dstFs, "dstRemote" to destination.name,
                "_async" to true, "_group" to group,
            ),
        )
        val jobId = started.optInt("jobid", -1)
        if (jobId < 0) {
            AppLogger.error(TAG, "[Rclone] copyfile returned no jobid; falling back to blocking copy")
            RcloneBridge.rpc(
                "operations/copyfile",
                mapOf(
                    "srcFs" to fs, "srcRemote" to pkg.key,
                    "dstFs" to dstFs, "dstRemote" to destination.name,
                ),
            )
            verifyDownload(pkg, destination, onProgress)
            return
        }

        var cancelled = false
        var lastBytes = 0L
        var lastTick = System.currentTimeMillis()
        var smoothed: Double? = null
        while (true) {
            Thread.sleep(POLL_INTERVAL_MS)

            if (!cancelled && cancelFlag?.isCancelled() == true) {
                cancelled = true
                runCatching { RcloneBridge.rpc("job/stop", mapOf("jobid" to jobId)) }
            }

            val status = runCatching {
                RcloneBridge.rpc("job/status", mapOf("jobid" to jobId))
            }.getOrNull()
            val finished = status?.optBoolean("finished") ?: false

            if (!finished) {
                val stats = runCatching {
                    RcloneBridge.rpc("core/stats", mapOf("group" to group))
                }.getOrNull()
                val moved = (stats?.optLong("bytes") ?: 0L).coerceAtLeast(0L)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTick) / 1000.0
                val reported = stats?.optDouble("speed", 0.0) ?: 0.0
                if (reported > 0) {
                    smoothed = reported
                } else if (elapsed > 0) {
                    val instant = (moved - lastBytes) / elapsed
                    smoothed = smoothed?.let { it * (1 - RATE_ALPHA) + instant * RATE_ALPHA }
                        ?: instant
                }
                lastBytes = moved
                lastTick = now
                onProgress?.invoke(
                    Progress(minOf(moved, pkg.size), pkg.size, smoothed ?: 0.0),
                )
                continue
            }

            val error = status?.optString("error").orEmpty()
            if (cancelled) {
                destination.delete()
                throw CancelledException()
            }
            if (error.isNotEmpty()) {
                destination.delete()
                throw UploadException(error)
            }
            break
        }
        verifyDownload(pkg, destination, onProgress)
    }

    private fun verifyDownload(
        pkg: RemotePackage,
        destination: File,
        onProgress: ((Progress) -> Unit)?,
    ) {
        val local = destination.length()
        if (pkg.size > 0 && local != pkg.size) {
            destination.delete()
            throw UploadException(
                "Download verification failed: got $local bytes but the backup is ${pkg.size} bytes.",
            )
        }
        onProgress?.invoke(Progress(pkg.size, pkg.size))
        AppLogger.info(TAG, "[Rclone] downloaded ${pkg.displayName} ($local bytes, verified)")
    }

    fun download(
        pkg: RemotePackage,
        remote: RcloneRemoteStore.Remote,
        destination: File,
        cancelFlag: CancelFlag? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        destination.delete()
        destination.parentFile?.mkdirs()

        if (!pkg.isChunked) {
            downloadWholeFile(pkg, remote, destination, cancelFlag, onProgress)
            return
        }

        val listing = RcloneBridge.rpc(
            "operations/list", mapOf("fs" to remote.fsSpec, "remote" to pkg.key)
        ).optJSONArray("list")
        val names = (0 until (listing?.length() ?: 0))
            .mapNotNull { listing?.optJSONObject(it)?.optString("Name") }
            .filter { it.isNotEmpty() }
            .sorted()
        if (names.size != pkg.partCount) {
            throw UploadException(
                "Expected ${pkg.partCount} parts but found ${names.size} — the upload is incomplete."
            )
        }

        val scratch = File(context.cacheDir, "minis-dl-${pkg.displayName}").apply { mkdirs() }
        try {
            destination.outputStream().buffered().use { out ->
                var written = 0L
                for (name in names) {
                    RcloneBridge.rpc(
                        "operations/copyfile",
                        mapOf(
                            "srcFs" to remote.fsSpec, "srcRemote" to "${pkg.key}/$name",
                            "dstFs" to scratch.absolutePath, "dstRemote" to name,
                        ),
                    )
                    val local = File(scratch, name)
                    local.inputStream().buffered().use { it.copyTo(out) }
                    written += local.length()
                    local.delete()
                    onProgress?.invoke(Progress(written, pkg.size))
                }
            }
        } finally {
            scratch.deleteRecursively()
        }
        AppLogger.info(TAG, "[Rclone] reassembled ${names.size} legacy part(s) -> ${destination.name}")
    }

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        for (pattern in TIME_PATTERNS) {
            runCatching {
                val f = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return f.parse(s)?.time
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Rclone"

        private const val POLL_INTERVAL_MS = 250L

        internal const val RETRY_BACKOFF_MS = 3_000L

        private const val MAX_STATUS_MISSES = 40

        private const val RATE_ALPHA = 0.3

        const val PARTIAL_SUFFIX = "partial"

        const val PARTS_DIR = ".minis-parts"

        private val TIME_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
    }
}
