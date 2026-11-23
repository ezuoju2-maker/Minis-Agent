package com.openminis.app.ui.settings.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupExporter
import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupManifest
import com.openminis.app.backup.BackupPackageReader
import com.openminis.app.backup.BackupRunController
import com.openminis.app.backup.BackupZip
import com.openminis.app.R
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val db get() = AppDatabase.getInstance(getApplication())


    private val prefs = app.getSharedPreferences("backup_ui", android.content.Context.MODE_PRIVATE)

    private val _selected = MutableStateFlow(loadSelectedCategories())
    val selected: StateFlow<Set<BackupCategory>> = _selected.asStateFlow()

    private val _encrypt = MutableStateFlow(prefs.getBoolean(KEY_ENCRYPT, false))
    val encrypt: StateFlow<Boolean> = _encrypt.asStateFlow()

    private val _maxFileSizeMB = MutableStateFlow(prefs.getInt(KEY_MAX_FILE_MB, MAX_FILE_UNLIMITED))
    val maxFileSizeMB: StateFlow<Int> = _maxFileSizeMB.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    private val runner = BackupRunController

    val exportRunning: StateFlow<Boolean> = runner.isRunning

    val isRunning: StateFlow<Boolean> = combine(_isRunning, runner.isRunning) { restore, export ->
        restore || export
    }.stateIn(viewModelScope, SharingStarted.Eagerly, runner.isRunning.value)

    fun stopExport() = runner.stop()

    private val _destinations =
        MutableStateFlow<List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>>(emptyList())
    val destinations: StateFlow<List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>> =
        _destinations.asStateFlow()

    val hasDestination: Boolean get() = _destinations.value.any { it.enabled }

    fun refreshDestinations() {
        _destinations.value = runCatching {
            com.openminis.app.backup.remote.RcloneRemoteStore(getApplication()).remotes
        }.getOrDefault(emptyList())
    }

    fun setDestinationEnabled(name: String, on: Boolean) {
        runCatching {
            com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                .setEnabled(name, on)
        }
        refreshDestinations()
    }

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = combine(_statusText, runner.statusText) { restore, export ->
        export ?: restore
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val exportReady: StateFlow<BackupRunController.ExportResult?> = runner.exportReady

    val saveState: StateFlow<BackupRunController.SaveState?> = runner.saveState

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = combine(_errorText, runner.errorText) { local, export ->
        local ?: export
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lastResult: StateFlow<BackupRunController.RunResult?> = runner.lastResult

    fun clearSettledSuccess() = runner.clearSettledSuccess()

    fun toggleCategory(category: BackupCategory, on: Boolean) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (on) add(category) else remove(category)
        }
        prefs.edit().putString(
            KEY_CATEGORIES, _selected.value.joinToString(",") { it.key }
        ).apply()
    }

    fun setEncrypt(on: Boolean) {
        _encrypt.value = on
        prefs.edit().putBoolean(KEY_ENCRYPT, on).apply()
    }

    fun setMaxFileSizeMB(value: Int) {
        _maxFileSizeMB.value = value
        prefs.edit().putInt(KEY_MAX_FILE_MB, value).apply()
    }

    private fun maxFileBytesOption(): Long? = when (val mb = _maxFileSizeMB.value) {
        MAX_FILE_UNLIMITED -> null
        MAX_FILE_NO_FILES -> 0L
        else -> mb.toLong() * 1024L * 1024L
    }

    fun clearError() {
        _errorText.value = null
        runner.clearError()
    }
    fun clearExportReady() = runner.clearExportReady()

    fun startExport(passphrase: String?) {
        if (isRunning.value) return
        val cats = _selected.value
        if (cats.isEmpty()) { _errorText.value = "Choose at least one thing to include."; return }
        val encrypting = _encrypt.value
        if (encrypting && passphrase.isNullOrEmpty()) {
            _errorText.value = "Set a passphrase to encrypt this backup."
            return
        }
        _errorText.value = null
        refreshDestinations()
        runner.start(
            getApplication(),
            BackupRunController.Request(
                categories = cats,
                maxFileBytes = maxFileBytesOption(),
                passphrase = passphrase?.takeIf { encrypting },
            ),
        )
    }

    fun saveExportTo(target: Uri) = runner.saveTo(getApplication(), target)

    fun clearSaveState() = runner.clearSaveState()

    private val history by lazy { BackupHistory.get(getApplication()) }

    private val _historyRecords = MutableStateFlow<List<BackupHistory.Record>>(emptyList())
    val historyRecords: StateFlow<List<BackupHistory.Record>> = _historyRecords.asStateFlow()

    fun refreshHistory() { _historyRecords.value = history.records() }

    init {
        viewModelScope.launch {
            runner.historyVersion.collect { _historyRecords.value = history.records() }
        }
    }

    sealed interface DeleteWithFilesResult {
        data class Success(val destinations: Int) : DeleteWithFilesResult

        data class Failed(val failures: List<String>) : DeleteWithFilesResult
    }

    suspend fun removeHistoryRecordWithFiles(id: String): DeleteWithFilesResult {
        val record = history.records().firstOrNull { it.id == id }
        val name = record?.packageName
        if (record == null || name.isNullOrEmpty()) {
            removeHistoryRecord(id)
            return DeleteWithFilesResult.Success(destinations = 0)
        }
        val targets = record.destinations.filter { it.succeeded }
        val failures = withContext(Dispatchers.IO) {
            val failed = mutableListOf<String>()
            try {
                val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                val hasNonLocalDest = targets.any { outcome ->
                    val remote = store.remotes.firstOrNull { it.name == outcome.name }
                    remote != null &&
                        !com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(remote.backend)
                }
                if (hasNonLocalDest) {
                    store.syncToRclone()
                }
                val uploader =
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                val localDelivery =
                    com.openminis.app.backup.remote.LocalFolderDelivery(getApplication())
                for (outcome in targets) {
                    val remote = store.remotes.firstOrNull { it.name == outcome.name }
                    if (remote == null) {
                        failed += "${outcome.name}: ${getApplication<android.app.Application>()
                            .getString(R.string.backup_delete_files_dest_missing)}"
                        continue
                    }
                    runCatching {
                        if (com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(remote.backend)) {
                            val treeUri = remote.params[
                                com.openminis.app.backup.remote.RcloneRemoteStore.PARAM_TREE_URI,
                            ].orEmpty()
                            if (treeUri.isEmpty()) {
                                throw IllegalStateException(
                                    "This folder destination is missing its location.",
                                )
                            }
                            localDelivery.delete(treeUri, name)
                        } else {
                            uploader.deletePackage(remote, name)
                        }
                    }
                        .onFailure {
                            AppLogger.error(
                                TAG,
                                "[Backup] deleting '$name' from '${outcome.name}' failed: ${it.message}",
                            )
                            failed += "${outcome.name}: ${it.message ?: it::class.java.simpleName}"
                        }
                }
            } catch (t: Throwable) {
                AppLogger.error(TAG, "[Backup] delete-with-files setup failed: ${t.message}")
                failed += t.message ?: t::class.java.simpleName
            }
            failed
        }
        if (failures.isNotEmpty()) return DeleteWithFilesResult.Failed(failures)
        removeHistoryRecord(id)
        return DeleteWithFilesResult.Success(destinations = targets.size)
    }

    fun removeHistoryRecord(id: String) {
        history.remove(id)
        _historyRecords.value = history.records()
    }


    private val _pending = MutableStateFlow<PendingRestore?>(null)
    val pending: StateFlow<PendingRestore?> = _pending.asStateFlow()

    private val _report = MutableStateFlow<BackupImporter.Report?>(null)
    val report: StateFlow<BackupImporter.Report?> = _report.asStateFlow()

    data class PendingRestore(
        val extractedRoot: File,
        val manifest: BackupManifest,
        val availableCategories: Set<BackupCategory>,
    )

    fun loadPackage(uri: Uri) {
        _isRunning.value = true
        _statusText.value = getApplication<android.app.Application>().getString(com.openminis.app.R.string.restore_progress_reading)
        _report.value = null
        viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) {
                    val zip = File(getApplication<Application>().cacheDir, "restore-pick.minisbak")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { inp ->
                        zip.outputStream().use { inp.copyTo(it) }
                    } ?: throw IllegalStateException("Could not open the selected file.")
                    extractAndInspect(zip)
                }
                setPending(pending)
                _statusText.value = null
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] load failed: ${e.message}")
                _errorText.value = e.message ?: "This file could not be read as a backup."
                _statusText.value = null
            } finally {
                _isRunning.value = false
            }
        }
    }

    private fun extractAndInspect(zip: File): PendingRestore {
        val extracted = File(getApplication<Application>().cacheDir, "restore-extract")
            .apply { deleteRecursively(); mkdirs() }
        var files = 0
        var bytes = 0L
        BackupZip.extract(zip, extracted) { name ->
            files += 1
            bytes += File(extracted, name).length()
            _openProgress.value = OpenProgress(files, bytes, name.substringAfterLast('/'))
        }
        val root = BackupZip.packageRoot(extracted)
        val manifest = BackupPackageReader(root).readManifest()
        val avail = manifest.categories.keys.mapNotNull(BackupCategory::fromKey).toSet()
        return PendingRestore(extracted, manifest, avail)
    }

    private fun setPending(pending: PendingRestore) {
        _pending.value = pending
        _restoreSelected.value = pending.availableCategories
    }


    fun listServerRemotes(): List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote> =
        com.openminis.app.backup.remote.RcloneRemoteStore(getApplication()).remotes

    private val _serverPackages =
        MutableStateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage>>(emptyList())
    val serverPackages: StateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage>> =
        _serverPackages.asStateFlow()

    fun listServerPackages(remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote) {
        _isRunning.value = true
        _statusText.value = getApplication<android.app.Application>().getString(com.openminis.app.R.string.restore_progress_listing)
        _serverPackages.value = emptyList()
        viewModelScope.launch {
            try {
                val pkgs = withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .listPackages(remote)
                }
                _serverPackages.value = pkgs
                _statusText.value = null
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] list server packages failed: ${e.message}")
                _errorText.value = e.message ?: "Could not list backups on that server."
                _statusText.value = null
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun clearServerPackages() { _serverPackages.value = emptyList() }


    private val _browseEntries =
        MutableStateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemoteEntry>>(emptyList())
    val browseEntries: StateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemoteEntry>> =
        _browseEntries.asStateFlow()

    private val _browsePath = MutableStateFlow("")
    val browsePath: StateFlow<String> = _browsePath.asStateFlow()

    private val _browsing = MutableStateFlow(false)
    val browsing: StateFlow<Boolean> = _browsing.asStateFlow()

    fun browseDestination(
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
        path: String = remote.path,
    ) {
        _browsing.value = true
        _errorText.value = null
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .listDirectory(remote, path)
                }
                _browsePath.value = path
                _browseEntries.value = entries
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] listing '$path' failed: ${e.message}")
                _errorText.value = e.message ?: "Could not list that folder."
            } finally {
                _browsing.value = false
            }
        }
    }

    fun deleteBrowsedPackage(
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
        entry: com.openminis.app.backup.remote.RcloneChunkedUpload.RemoteEntry,
    ) {
        if (entry.isDirectory) return
        val folder = _browsePath.value.ifEmpty { remote.path }
        _browsing.value = true
        _errorText.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication()).deletePackage(
                        remote,
                        com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage(
                            key = entry.path,
                            displayName = entry.name,
                            size = entry.size,
                            modified = entry.modified,
                            partCount = 1,
                        ),
                    )
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] delete '${entry.path}' failed: ${e.message}")
                _browsing.value = false
                _errorText.value = e.message ?: "Could not delete that backup."
                return@launch
            }
            browseDestination(remote, folder)
        }
    }

    fun clearBrowse() {
        _browseEntries.value = emptyList()
        _browsePath.value = ""
    }


    data class TransferInfo(
        val name: String,
        val bytesDone: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double,
        val secondsRemaining: Long?,
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes) else 0f
    }

    private val _transfer = MutableStateFlow<TransferInfo?>(null)
    val transfer: StateFlow<TransferInfo?> = _transfer.asStateFlow()

    private var downloadCancel: com.openminis.app.backup.remote.RcloneChunkedUpload.CancelFlag? = null

    fun cancelDownload() {
        AppLogger.info(TAG, "[Restore] user cancelled download")
        downloadCancel?.cancel()
    }


    private val _openStage = MutableStateFlow<Int?>(null)
    val openStage: StateFlow<Int?> = _openStage.asStateFlow()

    data class OpenProgress(val files: Int, val bytes: Long, val current: String)

    private val _openProgress = MutableStateFlow<OpenProgress?>(null)
    val openProgress: StateFlow<OpenProgress?> = _openProgress.asStateFlow()

    private val _restoreProgress = MutableStateFlow<BackupImporter.Progress?>(null)
    val restoreProgress: StateFlow<BackupImporter.Progress?> = _restoreProgress.asStateFlow()

    private val _restoreEtaSeconds = MutableStateFlow<Long?>(null)
    val restoreEtaSeconds: StateFlow<Long?> = _restoreEtaSeconds.asStateFlow()

    private val restoreEta = RestoreEta()

    private var restoreJob: kotlinx.coroutines.Job? = null

    private var openJob: kotlinx.coroutines.Job? = null

    fun cancelOpen() {
        AppLogger.info(TAG, "[Restore] user cancelled package open")
        openJob?.cancel()
    }

    fun resetRestoreState() {
        clearBrowse()
        _transfer.value = null
        _openStage.value = null
        _serverPackages.value = emptyList()
        _errorText.value = null
        _statusText.value = null
    }

    fun deleteServerPackage(
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
        pkg: com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage,
    ) {
        _isRunning.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .deletePackage(remote, pkg)
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] delete '${pkg.displayName}' failed: ${e.message}")
                _errorText.value = e.message ?: "Could not delete that backup."
            } finally {
                _isRunning.value = false
            }
            listServerPackages(remote)
        }
    }

    fun downloadServerPackage(
        pkg: com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage,
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    ) {
        _isRunning.value = true
        _report.value = null
        _errorText.value = null
        val flag = com.openminis.app.backup.remote.RcloneChunkedUpload.CancelFlag()
        downloadCancel = flag
        _transfer.value = TransferInfo(pkg.displayName, 0, pkg.size, 0.0, null)
        val dest = File(getApplication<Application>().cacheDir, "restore-server.minisbak")
        openJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .download(pkg, remote, dest, flag) { p ->
                            _transfer.value = TransferInfo(
                                pkg.displayName, p.bytesSent, p.totalBytes,
                                p.bytesPerSecond, p.secondsRemaining,
                            )
                        }
                }
                _transfer.value = null
                openPackageFile(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.info(TAG, "[Restore] download cancelled")
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { dest.delete() }
                _transfer.value = null
                _statusText.value = null
                throw e
            } catch (e: com.openminis.app.backup.remote.RcloneChunkedUpload.CancelledException) {
                AppLogger.info(TAG, "[Restore] download cancelled by user")
                _transfer.value = null
                _statusText.value = null
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] server download failed: ${e.message}")
                _errorText.value = e.message ?: "Could not download that backup."
                _transfer.value = null
                _statusText.value = null
            } finally {
                downloadCancel = null
                _isRunning.value = false
            }
        }
    }

    private suspend fun openPackageFile(file: File) {
        _openStage.value = 0
        val ticker = viewModelScope.launch {
            var stage = 0
            while (stage < OPEN_STAGE_COUNT - 1) {
                kotlinx.coroutines.delay(OPEN_STAGE_MS)
                stage++
                _openStage.value = stage
            }
        }
        try {
            val pending = withContext(Dispatchers.IO) { extractAndInspect(file) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            setPending(pending)
            _serverPackages.value = emptyList()
        } finally {
            ticker.cancel()
            _openStage.value = null
            _openProgress.value = null
            _statusText.value = null
        }
    }

    private val _restoreSelected = MutableStateFlow<Set<BackupCategory>>(emptySet())
    val restoreSelected: StateFlow<Set<BackupCategory>> = _restoreSelected.asStateFlow()

    fun toggleRestoreCategory(category: BackupCategory, on: Boolean) {
        _restoreSelected.value = _restoreSelected.value.toMutableSet().apply {
            if (on) add(category) else remove(category)
        }
    }

    fun cancelRestore() {
        _pending.value?.extractedRoot?.deleteRecursively()
        _pending.value = null
        _restoreSelected.value = emptySet()
    }

    fun dismissReport() { _report.value = null }

    fun startRestore(passphrase: String?) {
        val pending = _pending.value ?: return
        if (isRunning.value) return
        val cats = _restoreSelected.value
        if (cats.isEmpty()) { _errorText.value = "Choose at least one thing to restore."; return }
        if (pending.manifest.encryption != null && passphrase.isNullOrEmpty()) {
            _errorText.value = "This backup is encrypted. Enter its passphrase to restore."
            return
        }
        _isRunning.value = true
        _statusText.value = getApplication<android.app.Application>().getString(com.openminis.app.R.string.backup_restoring)
        restoreEta.reset()
        _restoreEtaSeconds.value = null
        restoreJob = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    BackupImporter(getApplication(), db).import(
                        pending.extractedRoot,
                        BackupImporter.Options(categories = cats, passphrase = passphrase),
                        onProgress = { line -> _statusText.value = line },
                        onCount = { p ->
                            _restoreProgress.value = p
                            _restoreEtaSeconds.value = restoreEta.update(p.categoryKey, p.done, p.total)
                        },
                    )
                }
                _report.value = report
                _restoreProgress.value = null
                _restoreEtaSeconds.value = null
                _pending.value?.extractedRoot?.deleteRecursively()
                _pending.value = null
                _statusText.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.info(TAG, "[Restore] cancelled by user")
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] failed: ${e.message}")
                _errorText.value = e.message ?: "Restore failed."
                _statusText.value = null
                _restoreProgress.value = null
                _restoreEtaSeconds.value = null
            } finally {
                if (restoreJob === coroutineContext[kotlinx.coroutines.Job]) {
                    _isRunning.value = false
                    restoreJob = null
                    restoreEta.reset()
                }
            }
        }
    }

    fun stopRunningRestore() {
        val job = restoreJob ?: return
        AppLogger.info(TAG, "[Restore] stop requested by user")
        job.cancel()
        restoreJob = null
        _isRunning.value = false
        _restoreProgress.value = null
        _restoreEtaSeconds.value = null
        restoreEta.reset()
        _statusText.value = null
    }


    private fun loadSelectedCategories(): Set<BackupCategory> {
        val raw = prefs.getString(KEY_CATEGORIES, null) ?: return BackupCategory.backupable.toSet()
        val restored = raw.split(",").mapNotNull(BackupCategory::fromKey)
            .filter { it in BackupCategory.backupable }.toSet()
        return restored.ifEmpty { BackupCategory.backupable.toSet() }
    }


    companion object {
        private const val TAG = "BackupViewModel"

        const val OPEN_STAGE_COUNT = 4
        private const val OPEN_STAGE_MS = 2_500L

        const val STOPPED_MARKER = BackupRunController.STOPPED_MARKER

        private const val KEY_CATEGORIES = "selectedCategories"
        private const val KEY_ENCRYPT = "encrypt"
        private const val KEY_MAX_FILE_MB = "maxFileSizeMB"
    }
}
