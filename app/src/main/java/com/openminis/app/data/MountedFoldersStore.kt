package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class MountedFoldersStore(private val context: Context) {

    @Serializable
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        val sourceDisplayName: String,
        val treeUri: String,
        val createdAt: Long = System.currentTimeMillis(),
        var isWritable: Boolean = true,
        var userAllowWrite: Boolean = true,
        var resolvedHostPath: String? = null,
    ) {
        val effectiveWritable: Boolean get() = isWritable && userAllowWrite
    }

    private val storeFile: File by lazy {
        File(context.filesDir, "minis-config/mounted-folders.json").apply {
            parentFile?.mkdirs()
        }
    }

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    var onChange: (() -> Unit)? = null

    init {
        loadFromDisk()
    }

    suspend fun add(
        treeUri: Uri,
        customName: String,
        userAllowWrite: Boolean = true,
    ): Entry? = mutex.withLock {
        val name = sanitizeName(customName).takeIf { it.isNotEmpty() } ?: return@withLock null
        if (_entries.value.any { it.name.equals(name, ignoreCase = true) }) return@withLock null
        if (_entries.value.size >= MAX_MOUNTS) return@withLock null

        val resolvedHostPath = resolvePosixPath(treeUri, context) ?: run {
            AppLogger.warning(TAG, "add: rejected non-resolvable URI $treeUri")
            return@withLock null
        }

        val sourceDisplayName = DocumentsContract.getTreeDocumentId(treeUri)
            .substringAfterLast(':', treeUri.lastPathSegment.orEmpty())
            .ifEmpty { name }
        val probedWritable = probeWritable(resolvedHostPath)
        val entry = Entry(
            name = name,
            sourceDisplayName = sourceDisplayName,
            treeUri = treeUri.toString(),
            isWritable = probedWritable,
            userAllowWrite = userAllowWrite,
            resolvedHostPath = resolvedHostPath,
        )
        _entries.value = _entries.value + entry
        saveToDisk(_entries.value)
        onChange?.invoke()
        AppLogger.info(
            TAG,
            "add: name=$name host=$resolvedHostPath writable=$probedWritable ${storageDiag(context)}",
        )
        entry
    }

    private fun storageDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return if (sdk >= Build.VERSION_CODES.R) {
            "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
        } else {
            val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val legacyView = if (sdk >= Build.VERSION_CODES.Q) {
                Environment.isExternalStorageLegacy().toString()
            } else {
                "n/a(pre-Q)"
            }
            "sdk=$sdk readGranted=$read writeGranted=$write legacyView=$legacyView"
        }
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val before = _entries.value
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return@withLock false
        before.firstOrNull { it.id == id }?.let { e ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(e.treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        _entries.value = after
        saveToDisk(after)
        onChange?.invoke()
        true
    }

    suspend fun rename(id: String, newName: String): Boolean = mutex.withLock {
        val trimmed = sanitizeName(newName).takeIf { it.isNotEmpty() } ?: return@withLock false
        if (_entries.value.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
            return@withLock false
        }
        _entries.value = _entries.value.map { e ->
            if (e.id == id) e.copy(name = trimmed) else e
        }
        saveToDisk(_entries.value)
        onChange?.invoke()
        true
    }

    suspend fun setUserAllowWrite(id: String, allow: Boolean): Boolean = mutex.withLock {
        var changed = false
        _entries.value = _entries.value.map { e ->
            if (e.id == id && e.userAllowWrite != allow) {
                changed = true
                e.copy(userAllowWrite = allow)
            } else e
        }
        if (changed) {
            saveToDisk(_entries.value)
            onChange?.invoke()
        }
        changed
    }

    suspend fun refreshWritability() = mutex.withLock {
        val before = _entries.value
        val after = before.map { e ->
            val probed = e.resolvedHostPath?.let { probeWritable(it) } ?: false
            if (probed != e.isWritable) e.copy(isWritable = probed) else e
        }
        if (after != before) {
            _entries.value = after
            saveToDisk(after)
            onChange?.invoke()
        }
    }

    suspend fun resolvePosixPath(treeUri: Uri, context: Context): String? =
        withContext(Dispatchers.IO) {
            val authority = treeUri.authority
            if (authority != EXTERNALSTORAGE_AUTHORITY) {
                AppLogger.warning(TAG, "resolvePosixPath: rejecting non-externalstorage authority=$authority")
                return@withContext null
            }
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext null
            val sep = docId.indexOf(':')
            val volume = if (sep < 0) docId else docId.substring(0, sep)
            val relPath = if (sep < 0) "" else docId.substring(sep + 1)

            val volumeRoot = resolveVolumeRoot(context, volume) ?: run {
                AppLogger.warning(TAG, "resolvePosixPath: unknown volume=$volume in docId=$docId")
                return@withContext null
            }
            val full = if (relPath.isEmpty()) File(volumeRoot)
                else File(volumeRoot, relPath)
            if (!full.exists() || !full.isDirectory) {
                AppLogger.warning(TAG, "resolvePosixPath: path missing or not dir: ${full.absolutePath}")
                return@withContext null
            }
            val children = full.list()
            if (children == null) {
                AppLogger.warning(
                    TAG,
                    "resolvePosixPath: ${full.absolutePath} canRead=${full.canRead()} but list()=null " +
                        "(readdir blocked by scoped storage) — mount will read EMPTY on this device",
                )
            } else {
                AppLogger.info(TAG, "resolvePosixPath: ${full.absolutePath} ok childCount=${children.size}")
            }
            full.absolutePath
        }

    fun probeWritable(hostPath: String): Boolean {
        val dir = File(hostPath)
        if (!dir.isDirectory) return false
        val probe = File(dir, ".minis-probe-${UUID.randomUUID()}")
        return runCatching {
            probe.outputStream().use { it.write(0) }
            true
        }.onFailure { e ->
            AppLogger.warning(TAG, "probeWritable: $hostPath not writable: ${e.message}")
        }.getOrDefault(false).also {
            runCatching { probe.delete() }
        }
    }

    private fun resolveVolumeRoot(context: Context, volume: String): String? {
        if (volume.equals("primary", ignoreCase = true)) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.firstOrNull { it.uuid?.equals(volume, ignoreCase = true) == true }
                ?.directory?.absolutePath?.let { return it }
        }
        val fallback = File("/storage/$volume")
        return if (fallback.isDirectory) fallback.absolutePath else null
    }

    private fun loadFromDisk() {
        if (!storeFile.isFile) return
        runCatching {
            val text = storeFile.readText()
            val parsed = JSON.decodeFromString<List<Entry>>(text)
            _entries.value = parsed
        }
    }

    private suspend fun saveToDisk(list: List<Entry>) = withContext(Dispatchers.IO) {
        runCatching {
            storeFile.writeText(JSON.encodeToString(list))
        }
    }

    private fun sanitizeName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "." || trimmed == "..") return ""
        if (trimmed.contains('/') || trimmed.contains(' ')) return ""
        return trimmed.take(64)
    }

    companion object {
        const val MAX_MOUNTS = 10
        private const val TAG = "MountedFolders"
        private const val EXTERNALSTORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

object SafMountHelper {
    fun buildPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    fun handlePickerResult(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    fun treeDisplayPath(uri: Uri): String =
        DocumentsContract.getTreeDocumentId(uri)
}
