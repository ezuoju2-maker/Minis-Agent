package com.openminis.app.backup

import android.content.Context
import android.net.Uri
import com.openminis.app.R
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object BackupRunController {

    private const val TAG = "BackupRunController"

    const val STOPPED_MARKER = "stopped"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Request(
        val categories: Set<BackupCategory>,
        val maxFileBytes: Long?,
        val passphrase: String?,
    ) {
        val encrypted: Boolean get() = !passphrase.isNullOrEmpty()
    }

    data class ExportResult(
        val backupId: String,
        val packageFile: File,
        val totalBytes: Long,
        val skippedFiles: Int,
    )

    data class RunResult(
        val totalBytes: Long,
        val skippedFiles: Int,
        val destinations: List<BackupHistory.DestinationOutcome>,
        val localCopyRemoved: Boolean = false,
        val savedAs: String? = null,
    ) {
        val allDelivered: Boolean
            get() = destinations.isNotEmpty() && destinations.all { it.succeeded }

        val localOnly: Boolean get() = destinations.isEmpty()

        val settled: Boolean get() = allDelivered || savedAs != null
    }

    sealed interface SaveState {
        data class Saving(val percent: Int) : SaveState
        data class Saved(val name: String) : SaveState
        data class Failed(val message: String) : SaveState
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _exportReady = MutableStateFlow<ExportResult?>(null)
    val exportReady: StateFlow<ExportResult?> = _exportReady.asStateFlow()

    private val _lastResult = MutableStateFlow<RunResult?>(null)
    val lastResult: StateFlow<RunResult?> = _lastResult.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState?>(null)
    val saveState: StateFlow<SaveState?> = _saveState.asStateFlow()

    private val _historyVersion = MutableStateFlow(0L)
    val historyVersion: StateFlow<Long> = _historyVersion.asStateFlow()

    @Volatile private var runJob: Job? = null

    fun clearError() { _errorText.value = null }
    fun clearExportReady() { _exportReady.value = null }
    fun clearSaveState() { _saveState.value = null }

    fun clearSettledSuccess() {
        if (_isRunning.value) return
        val r = _lastResult.value ?: return
        if (r.settled) _lastResult.value = null
    }

    fun stop() {
        val job = runJob ?: return
        AppLogger.info(TAG, "[Backup] user requested stop")
        job.cancel()
    }

    fun start(context: Context, request: Request): Boolean {
        if (_isRunning.value) return false
        val app = context.applicationContext
        val cats = request.categories
        _isRunning.value = true
        _errorText.value = null
        _lastResult.value = null
        _exportReady.value = null
        _saveState.value = null
        _statusText.value = "Starting…"

        val history = BackupHistory.get(app)
        val log = mutableListOf<BackupHistory.LogEntry>()
        var record = BackupHistory.Record(
            backupId = "",
            startedAt = System.currentTimeMillis(),
            status = BackupHistory.Status.RUNNING,
            categories = cats.map { it.key }.sorted(),
            encrypted = request.encrypted,
        )
        writeHistory(history, record)

        fun note(line: String, problem: Boolean = false, transient: Boolean = false) {
            val entry = BackupHistory.LogEntry(System.currentTimeMillis(), line, problem, transient)
            synchronized(log) { BackupLogRules.append(log, entry) }
        }
        fun logSnapshot() = synchronized(log) { log.toList() }
        val historyWriter = ThrottledWriter(
            schedule = { delayMs, block -> scope.launch { kotlinx.coroutines.delay(delayMs); block() } },
            write = { writeHistory(history, record.copy(log = logSnapshot())) },
        )
        fun progress(line: String, transient: Boolean = false) {
            _statusText.value = line
            note(line, transient = transient)
            historyWriter.request()
        }

        runJob = scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    BackupExporter(app, AppDatabase.getInstance(app)).export(
                        BackupExporter.Options(
                            categories = cats,
                            maxFileBytes = request.maxFileBytes,
                            includeCredentials = true,
                            passphrase = request.passphrase,
                        ),
                    ) { line -> progress(line) }
                }

                val outcomes = withContext(Dispatchers.IO) {
                    val job = coroutineContext[kotlinx.coroutines.Job]
                    deliverToDestinations(
                        app, summary.packageFile, summary.backupId,
                        isCancelled = { job?.isActive == false },
                    ) { line, transient -> progress(line, transient) }
                }
                outcomes.forEach {
                    note(
                        if (it.succeeded) "Delivered to ${it.name}"
                        else "Delivery to ${it.name} failed: ${it.detail}",
                        problem = !it.succeeded,
                    )
                }

                var localRemoved = false
                if (outcomes.isNotEmpty() && outcomes.all { it.succeeded }) {
                    val freed = summary.packageFile.length()
                    if (withContext(Dispatchers.IO) { summary.packageFile.delete() }) {
                        localRemoved = true
                        val msg = app.getString(R.string.backup_local_removed, humanBytesPlain(freed))
                        AppLogger.info(TAG, "[Backup] $msg")
                        note(msg)
                    }
                } else if (outcomes.isNotEmpty()) {
                    note(app.getString(R.string.backup_local_kept))
                } else {
                    note(app.getString(R.string.backup_result_local_pending))
                }

                _exportReady.value = ExportResult(
                    backupId = summary.backupId,
                    packageFile = summary.packageFile,
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                )
                _lastResult.value = RunResult(
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                    destinations = outcomes,
                    localCopyRemoved = localRemoved,
                )
                val failed = outcomes.filterNot { it.succeeded }
                if (failed.isNotEmpty()) {
                    _errorText.value = "Saved locally, but delivery failed for: " +
                        failed.joinToString("; ") { "${it.name}: ${it.detail ?: "failed"}" }
                }
                record = record.copy(
                    backupId = summary.backupId,
                    finishedAt = System.currentTimeMillis(),
                    status = if (failed.isEmpty() && summary.skippedFiles == 0) {
                        BackupHistory.Status.SUCCEEDED
                    } else {
                        BackupHistory.Status.COMPLETED_WITH_ISSUES
                    },
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                    skippedEntries = summary.skippedPaths.map {
                        BackupHistory.SkippedEntry(it.path, it.size)
                    },
                    packageName = summary.packageFile.name,
                    destinations = outcomes,
                    log = logSnapshot(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.info(TAG, "[Backup] export cancelled by user")
                note(STOPPED_MARKER, problem = true)
                record = record.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = BackupHistory.Status.FAILED,
                    errorMessage = STOPPED_MARKER,
                    log = logSnapshot(),
                )
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] export failed: ${e.message}")
                _errorText.value = e.message ?: "Backup failed."
                note(e.message ?: "Backup failed.", problem = true)
                record = record.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = BackupHistory.Status.FAILED,
                    errorMessage = e.message,
                    log = logSnapshot(),
                )
            } finally {
                runJob = null
                _statusText.value = null
                historyWriter.close()
                _isRunning.value = false
            }
        }
        com.openminis.app.service.BackupForegroundService.start(app)
        return true
    }

    fun saveTo(context: Context, target: Uri) {
        val ready = _exportReady.value ?: return
        val app = context.applicationContext
        if (_saveState.value is SaveState.Saving) return
        _saveState.value = SaveState.Saving(0)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    copyWithProgress(app, ready.packageFile, target) { pct ->
                        _saveState.value = SaveState.Saving(pct)
                    }
                }
                val name = displayName(app, target) ?: ready.packageFile.name
                _saveState.value = SaveState.Saved(name)
                _lastResult.value = _lastResult.value?.copy(savedAs = name)
                recordSavedToDevice(app, ready.backupId, name)
                AppLogger.info(TAG, "[Backup] saved to device as $name")
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] save to device failed: ${e.message}")
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(app.contentResolver, target)
                }
                _saveState.value = SaveState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }


    private fun writeHistory(history: BackupHistory, record: BackupHistory.Record) {
        history.upsert(record)
        _historyVersion.value = _historyVersion.value + 1
    }

    private fun recordSavedToDevice(context: Context, backupId: String, name: String) {
        if (backupId.isEmpty()) return
        val history = BackupHistory.get(context)
        val rec = history.records().firstOrNull { it.backupId == backupId } ?: return
        val line = context.getString(R.string.backup_saved_to_device_log, name)
        writeHistory(
            history,
            rec.copy(log = rec.log + BackupHistory.LogEntry(System.currentTimeMillis(), line, false)),
        )
    }

    internal fun copyWithProgress(
        context: Context,
        source: File,
        target: Uri,
        onPercent: (Int) -> Unit,
    ) {
        val total = source.length()
        val out = context.contentResolver.openOutputStream(target, "w")
            ?: throw IllegalStateException("Could not open the chosen location for writing.")
        out.use { sink ->
            source.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    copied += n
                    val pct = if (total > 0) (copied * 100 / total).toInt() else 100
                    if (pct != lastPct) { lastPct = pct; onPercent(pct) }
                }
                sink.flush()
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun deliverToDestinations(
        context: Context,
        packageFile: File,
        backupId: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (String, Boolean) -> Unit,
    ): List<BackupHistory.DestinationOutcome> {
        val store = com.openminis.app.backup.remote.RcloneRemoteStore(context)
        val enabled = store.enabledRemotes
        if (enabled.isEmpty()) return emptyList()
        if (enabled.any { !com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(it.backend) }) {
            store.syncToRclone()
        }
        val uploader = com.openminis.app.backup.remote.RcloneChunkedUpload(context)
        val localDelivery = com.openminis.app.backup.remote.LocalFolderDelivery(context)
        val outcomes = mutableListOf<BackupHistory.DestinationOutcome>()
        for (remote in enabled) {
            if (isCancelled()) {
                AppLogger.info(TAG, "[Backup] delivery stopped before '${remote.name}'")
                break
            }
            try {
                onProgress("Sending to ${remote.name}…", false)
                if (com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(remote.backend)) {
                    val treeUri = remote.params[
                        com.openminis.app.backup.remote.RcloneRemoteStore.PARAM_TREE_URI,
                    ].orEmpty()
                    if (treeUri.isEmpty()) {
                        throw IllegalStateException("This folder destination is missing its location.")
                    }
                    val gate = PercentGate()
                    localDelivery.deliver(packageFile, treeUri, isCancelled) { sent, total ->
                        val pct = if (total > 0) (sent * 100 / total) else 0
                        if (gate.admit(pct.toInt())) onProgress("Saving to ${remote.name}… $pct%", true)
                    }
                } else {
                    uploader.upload(packageFile, remote, backupId, isCancelled = isCancelled) { p ->
                        val pct = if (p.totalBytes > 0) (p.bytesSent * 100 / p.totalBytes) else 0
                        onProgress("Sending to ${remote.name}… $pct%", true)
                    }
                }
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        remote.name, succeeded = true, kind = remote.backend, path = remote.path,
                    ),
                )
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] delivery to ${remote.name} failed: ${e.message}")
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        remote.name, succeeded = false, detail = e.message ?: "failed",
                        kind = remote.backend, path = remote.path,
                    ),
                )
            }
        }
        return outcomes
    }

    internal fun humanBytesPlain(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> String.format(java.util.Locale.US, "%.0f kB", bytes / 1e3)
        else -> "$bytes B"
    }
}
