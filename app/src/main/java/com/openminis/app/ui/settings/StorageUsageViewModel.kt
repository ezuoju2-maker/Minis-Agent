package com.openminis.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.data.session.OrphanedSessionFiles
import com.openminis.app.data.session.SessionStorage
import com.openminis.app.data.session.SessionTree
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class SessionStorageInfo(
    val id: String,
    val title: String?,
    val minisSize: Long,
    val mediaSize: Long,
    val childCount: Int = 0,
) {
    val totalSize: Long get() = minisSize + mediaSize
}

internal object SessionStorageRows {

    fun sorted(rows: Collection<SessionStorageInfo>): List<SessionStorageInfo> =
        rows.sortedWith(compareByDescending<SessionStorageInfo> { it.totalSize }.thenBy { it.id })

    fun applyMeasurements(
        rows: List<SessionStorageInfo>,
        measured: Map<String, SessionStorageInfo?>,
    ): List<SessionStorageInfo> {
        if (measured.isEmpty()) return rows
        val byId = LinkedHashMap<String, SessionStorageInfo>()
        rows.forEach { byId[it.id] = it }
        for ((id, info) in measured) {
            if (info == null) byId.remove(id) else byId[id] = info
        }
        return sorted(byId.values)
    }

    fun total(rows: List<SessionStorageInfo>): Long = rows.sumOf { it.totalSize }

    fun measureRoots(
        rootIds: Collection<String>,
        sessions: List<ChatSessionEntity>,
        storage: SessionStorage,
    ): Map<String, SessionStorageInfo?> {
        if (rootIds.isEmpty()) return emptyMap()
        val byId = sessions.associateBy { it.id }
        val parentOf: Map<String, String?> = sessions.associate { it.id to it.parentSessionId }
        val roots = rootIds.map { id -> if (id in byId) SessionTree.rootOf(id, parentOf) else id }.toSet()
        val membersByRoot = roots.associateWith { root ->
            if (root in byId) SessionTree.membersOf(root, parentOf).ifEmpty { listOf(root) } else emptyList()
        }
        val media = storage.mediaSizesBySession(membersByRoot.values.flatten().toSet())
        return roots.associateWith { root ->
            val session = byId[root] ?: return@associateWith null
            val members = membersByRoot.getValue(root)
            SessionStorageInfo(
                id = root,
                title = session.title,
                minisSize = members.sumOf { storage.minisSize(it) },
                mediaSize = members.sumOf { media[it] ?: 0L },
                childCount = members.size - 1,
            )
        }
    }
}

internal class StorageUsageViewModel(
    context: Context,
    private val repo: ChatRepository,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val storage = SessionStorage(appContext.filesDir)

    data class UiState(
        val isLoading: Boolean = true,
        val shellSize: Long = 0L,
        val dbSize: Long = 0L,
        val logsCachesSize: Long = 0L,
        val rows: List<SessionStorageInfo> = emptyList(),
        val isClearingLogs: Boolean = false,
        val orphanBytes: Long = 0L,
        val isCleaningOrphans: Boolean = false,
        val selecting: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
        val batchBusy: Boolean = false,
    ) {
        val totalSessionSize: Long get() = SessionStorageRows.total(rows)
        val selectedSize: Long get() = rows.filter { it.id in selectedIds }.sumOf { it.totalSize }
    }

    sealed interface BatchResult {
        data class Cleared(val sessions: Int, val freed: Long, val failedPaths: List<String>) : BatchResult
        data class Deleted(val sessions: Int, val freed: Long) : BatchResult
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val orphans = OrphanedSessionFiles(storage)

    private val _orphanResult = MutableStateFlow<OrphanedSessionFiles.Result?>(null)
    val orphanResult: StateFlow<OrphanedSessionFiles.Result?> = _orphanResult.asStateFlow()

    private val _result = MutableStateFlow<BatchResult?>(null)
    val result: StateFlow<BatchResult?> = _result.asStateFlow()

    private var shellStale = false

    init {
        scanAll()
    }

    private fun scanAll() {
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val scanned = withContext(Dispatchers.IO) {
                val listedFolders = orphans.listFolders()
                val allSessions = repo.dao.listSessions()
                val orphanScan = orphans.scan(
                    listedFolders, allSessions.mapTo(HashSet()) { it.id }, sessionsInUse(), System.currentTimeMillis(),
                )
                val parentOf = allSessions.associate { it.id to it.parentSessionId }
                val rows = aggregateSessionStorage(allSessions, storage).map { (session, minis, media) ->
                    SessionStorageInfo(
                        id = session.id,
                        title = session.title,
                        minisSize = minis,
                        mediaSize = media,
                        childCount = SessionTree.membersOf(session.id, parentOf).size - 1,
                    )
                }
                UiState(
                    isLoading = false,
                    shellSize = SessionStorage.directorySize(File(appContext.filesDir, "ubuntu-rootfs")),
                    dbSize = databaseSize(appContext),
                    logsCachesSize = LogsAndCachesCleaner.size(appContext),
                    orphanBytes = orphanScan.totalBytes,
                    rows = SessionStorageRows.sorted(rows),
                )
            }
            _state.update { scanned.copy(selecting = it.selecting, selectedIds = it.selectedIds) }
            AppLogger.info(
                TAG,
                "full scan: ${scanned.rows.size} sessions in ${System.currentTimeMillis() - started}ms",
            )
        }
    }

    fun onListShown() {
        if (!shellStale) return
        shellStale = false
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                SessionStorage.directorySize(File(appContext.filesDir, "ubuntu-rootfs"))
            }
            _state.update { it.copy(shellSize = size) }
        }
    }

    fun onRootfsOpened() {
        shellStale = true
    }

    fun onSessionMeasured(sessionId: String, minisSize: Long, mediaSize: Long) {
        _state.update { st ->
            val row = st.rows.firstOrNull { it.id == sessionId } ?: return@update st
            if (row.minisSize == minisSize && row.mediaSize == mediaSize) return@update st
            AppLogger.info(TAG, "incremental: ${sessionId.take(8)} ${row.totalSize}B -> ${minisSize + mediaSize}B (detail)")
            st.copy(
                rows = SessionStorageRows.applyMeasurements(
                    st.rows,
                    mapOf(sessionId to row.copy(minisSize = minisSize, mediaSize = mediaSize)),
                ),
            )
        }
    }

    private fun sessionsInUse(): Set<String> {
        val t = com.openminis.app.service.SessionActivityTracker
        return t.activeSessions.value + t.presentSessions.value + t.scheduledSessions.value + t.processingSessions.value
    }

    private suspend fun scanOrphans(): OrphanedSessionFiles.Scan = withContext(Dispatchers.IO) {
        val listed = orphans.listFolders()
        val ids = repo.dao.listSessions().mapTo(HashSet()) { it.id }
        orphans.scan(listed, ids, sessionsInUse(), System.currentTimeMillis())
    }

    fun previewOrphanCleanup(onResult: (OrphanedSessionFiles.Scan) -> Unit) {
        viewModelScope.launch {
            val scan = scanOrphans()
            _state.update { it.copy(orphanBytes = scan.totalBytes) }
            onResult(scan)
        }
    }

    fun cleanOrphans() {
        if (_state.value.isCleaningOrphans) return
        _state.update { it.copy(isCleaningOrphans = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { orphans.delete(scanOrphans()) }
            AppLogger.info(
                TAG,
                "[OrphanCleanup] removed=${result.removedCount} freed=${result.freedBytes} " +
                    "kept=${result.keptBytes} failed=${result.failedPaths.size}",
            )
            _state.update {
                it.copy(isCleaningOrphans = false, orphanBytes = (it.orphanBytes - result.freedBytes).coerceAtLeast(0L))
            }
            _orphanResult.value = result
        }
    }

    fun consumeOrphanResult() {
        _orphanResult.value = null
    }

    fun clearLogsAndCaches() {
        if (_state.value.isClearingLogs) return
        _state.update { it.copy(isClearingLogs = true) }
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                LogsAndCachesCleaner.clear(appContext)
                LogsAndCachesCleaner.size(appContext)
            }
            _state.update { it.copy(isClearingLogs = false, logsCachesSize = size) }
        }
    }


    fun startSelecting(initial: String? = null) {
        _state.update { it.copy(selecting = true, selectedIds = setOfNotNull(initial)) }
    }

    fun stopSelecting() {
        _state.update { it.copy(selecting = false, selectedIds = emptySet()) }
    }

    fun toggle(id: String) {
        _state.update { st ->
            st.copy(selectedIds = if (id in st.selectedIds) st.selectedIds - id else st.selectedIds + id)
        }
    }

    fun toggleSelectAll() {
        _state.update { st ->
            val all = st.rows.map { it.id }.toSet()
            st.copy(selectedIds = if (st.selectedIds.containsAll(all)) emptySet() else all)
        }
    }

    fun clearSelectedFiles() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.batchBusy) return
        _state.update { it.copy(batchBusy = true) }
        viewModelScope.launch {
            val before = _state.value.rows.filter { it.id in ids }.sumOf { it.totalSize }
            val (failed, measured) = withContext(Dispatchers.IO) {
                val sessions = repo.dao.listSessions()
                val parentOf = sessions.associate { it.id to it.parentSessionId }
                val failed = ids.flatMap { root ->
                    SessionTree.membersOf(root, parentOf).ifEmpty { listOf(root) }
                        .flatMap { storage.deleteFilesDetailed(it).failedPaths }
                }
                failed to SessionStorageRows.measureRoots(ids, sessions, storage)
            }
            val after = measured.values.sumOf { it?.totalSize ?: 0L }
            _state.update {
                it.copy(
                    rows = SessionStorageRows.applyMeasurements(it.rows, measured),
                    batchBusy = false,
                    selecting = false,
                    selectedIds = emptySet(),
                )
            }
            AppLogger.info(TAG, "incremental: cleared ${ids.size} sessions, ${before - after}B freed, ${failed.size} failed")
            _result.value = BatchResult.Cleared(ids.size, (before - after).coerceAtLeast(0L), failed)
        }
    }

    fun deleteSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.batchBusy) return
        _state.update { it.copy(batchBusy = true) }
        viewModelScope.launch {
            val before = _state.value.rows.filter { it.id in ids }.sumOf { it.totalSize }
            val (measured, db) = withContext(Dispatchers.IO) {
                ids.forEach { SessionDeleter.deleteTree(appContext, repo, it, "storage-multi") }
                SessionStorageRows.measureRoots(ids, repo.dao.listSessions(), storage) to databaseSize(appContext)
            }
            val remaining = measured.values.sumOf { it?.totalSize ?: 0L }
            val deleted = measured.values.count { it == null }
            _state.update {
                it.copy(
                    rows = SessionStorageRows.applyMeasurements(it.rows, measured),
                    dbSize = db,
                    batchBusy = false,
                    selecting = false,
                    selectedIds = emptySet(),
                )
            }
            AppLogger.info(TAG, "incremental: deleted $deleted/${ids.size} sessions, ${before - remaining}B freed")
            _result.value = BatchResult.Deleted(deleted, (before - remaining).coerceAtLeast(0L))
        }
    }

    fun consumeResult() {
        _result.value = null
    }

    companion object {
        private const val TAG = "StorageUsage"

        fun factory(context: Context, repo: ChatRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StorageUsageViewModel(context.applicationContext, repo) as T
            }
    }
}

internal fun databaseSize(context: Context): Long {
    val dbFile = context.getDatabasePath("minis.db")
    var size = if (dbFile.exists()) dbFile.length() else 0L
    val wal = File(dbFile.path + "-wal")
    val shm = File(dbFile.path + "-shm")
    if (wal.exists()) size += wal.length()
    if (shm.exists()) size += shm.length()
    return size
}
