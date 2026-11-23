package com.openminis.app.ui.sandbox

import android.text.format.Formatter
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class FileSortKey { NAME, MODIFIED, SIZE, KIND }

@Immutable
data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modifiedMs: Long = 0L,
) {
    val iconRes: String
        get() {
            if (isDirectory) return "folder"
            val ext = file.extension.lowercase()
            return when (ext) {
                "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
                "log", "csv" -> "text"

                "sh", "bash", "zsh", "fish" -> "terminal"
                "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
                "rs", "go", "rb", "php", "lua", "pl" -> "code"

                "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "ico" -> "image"
                "mp3", "wav", "aac", "flac", "ogg", "m4a" -> "audio"
                "mp4", "mov", "avi", "mkv", "webm" -> "video"
                "zip", "tar", "gz", "bz2", "xz", "7z", "rar" -> "archive"
                "pdf" -> "pdf"
                "apk", "deb", "rpm" -> "package"
                "db", "sqlite", "sqlite3" -> "database"
                "so", "dylib", "a" -> "library"
                else -> "file"
            }
        }

    val formattedSize: String
        get() = Formatter.formatFileSize(null, size)

    val isPreviewable: Boolean
        get() = isTextFile || isImageFile

    val isTextFile: Boolean
        get() {
            val ext = file.extension.lowercase()
            return ext in setOf(
                "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
                "log", "csv", "sh", "bash", "zsh", "fish",
                "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
                "rs", "go", "rb", "php", "lua", "pl", "html", "css", "scss",
                "toml", "env", "gitignore", "dockerfile", "makefile",
            ) || ext.isEmpty()
        }

    val isImageFile: Boolean
        get() {
            val ext = file.extension.lowercase()
            return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
        }

    val isMarkdownFile: Boolean
        get() = file.extension.lowercase() in setOf("md", "markdown", "mdown", "mkd")

    val isHtmlFile: Boolean
        get() = file.extension.lowercase() in setOf("html", "htm", "xhtml")

    val isAudioFile: Boolean
        get() = file.extension.lowercase() in setOf("mp3", "wav", "aac", "flac", "ogg", "m4a")

    val isVideoFile: Boolean
        get() = file.extension.lowercase() in setOf("mp4", "mov", "avi", "mkv", "webm")

    val isPdfFile: Boolean
        get() = file.extension.lowercase() == "pdf"

    val isCsvFile: Boolean
        get() = file.extension.lowercase() in setOf("csv", "tsv")

    val isJsonFile: Boolean
        get() = file.extension.lowercase() == "json"

    val isApkFile: Boolean
        get() = file.extension.lowercase() in setOf("apk", "apks", "xapk")

    val isArchiveFile: Boolean
        get() = file.extension.lowercase() in setOf("zip", "jar", "apk", "aar")

    val isOfficeFile: Boolean
        get() = file.extension.lowercase() in setOf(
            "xlsx", "xls", "docx", "doc", "pptx", "ppt", "odt", "ods", "odp",
        )

    val formattedDate: String
        get() {
            if (modifiedMs <= 0L) return ""
            val date = Date(modifiedMs)
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { time = date }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            if (sameDay) return timeFormatter.format(date)
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            if (isYesterday) return "Yesterday"
            val daysAgo = (now.timeInMillis - then.timeInMillis) / (24L * 60 * 60 * 1000)
            if (daysAgo in 0..6) return weekdayFormatter.format(date)
            return shortDateFormatter.format(date)
        }

    companion object {
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
        private val shortDateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun from(file: File): FileItem? {
            if (!file.exists()) return null
            val isSymlink = Files.isSymbolicLink(file.toPath())
            val resolved = if (isSymlink) {
                try {
                    Files.readSymbolicLink(file.toPath()).toFile().let { target ->
                        if (target.isAbsolute) target else File(file.parentFile, target.path)
                    }
                } catch (_: Exception) {
                    file
                }
            } else file

            return FileItem(
                file = file,
                name = file.name,
                isDirectory = resolved.isDirectory,
                isSymlink = isSymlink,
                size = if (resolved.isDirectory) 0L else resolved.length(),
                modifiedMs = resolved.lastModified(),
            )
        }
    }
}

data class FileBrowserUiState(
    val items: List<FileItem> = emptyList(),
    val pathComponents: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isEmpty: Boolean = false,
    val canGoBack: Boolean = false,
    val currentPath: String = "",
    val errorMessage: String? = null,
    val sortKey: FileSortKey = FileSortKey.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = false,
    val currentLinuxPath: String? = null,
    val refusedMountPointName: String? = null,
)

class FileBrowserViewModel(
    private val rootPath: File,
    initialPath: File? = null,
    private val rootLabel: String = rootPath.name,
    private val linuxRootPath: String? = null,
    private val sessionId: String? = null,
    private val appContext: android.content.Context? = null,
    private val displayLinuxPrefix: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private var rawItems: List<FileItem> = emptyList()

    private var relativePath: String = initialPath?.let { init ->
        if (init.absolutePath.startsWith(rootPath.absolutePath)) {
            init.absolutePath.removePrefix(rootPath.absolutePath).trim('/')
        } else ""
    }.orEmpty()

    private val initialRelativePath: String = relativePath

    private val currentHostPath: File
        get() = resolveCurrentHostPath()

    private fun resolveCurrentHostPath(): File {
        val linuxRoot = linuxRootPath
        if (linuxRoot != null) {
            val linuxPath = if (relativePath.isEmpty()) linuxRoot
                            else "${linuxRoot.trimEnd('/')}/$relativePath"
            val sid = sessionId
            val ctx = appContext
            if (sid != null && ctx != null) {
                com.openminis.app.sandbox.PRootKernel
                    .resolveSessionHostPath(sid, linuxPath, ctx)
                    ?.let { return it }
            }
            com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)?.let { return it }
        }
        return if (relativePath.isEmpty()) rootPath else File(rootPath, relativePath)
    }

    init {
        val persisted = appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.getBoolean(PREF_KEY_SHOW_HIDDEN, false)
            ?: false
        if (persisted) {
            _uiState.value = _uiState.value.copy(showHidden = true)
        }
        updatePathComponents()
        loadItems()
    }

    fun setShowHidden(value: Boolean) {
        if (_uiState.value.showHidden == value) return
        _uiState.value = _uiState.value.copy(showHidden = value)
        appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(PREF_KEY_SHOW_HIDDEN, value)
            ?.apply()
        loadItems()
    }

    fun navigateTo(item: FileItem) {
        if (!item.isDirectory) return
        relativePath = if (relativePath.isEmpty()) item.name else "$relativePath/${item.name}"
        updatePathComponents()
        loadItems()
    }

    fun goBack(): Boolean {
        if (relativePath == initialRelativePath) return false
        val parent = relativePath.substringBeforeLast('/', "")
        val initial = initialRelativePath
        relativePath = when {
            initial.isEmpty() -> parent
            parent.isEmpty() -> initial
            parent == initial || parent.startsWith("$initial/") -> parent
            else -> initial
        }
        updatePathComponents()
        loadItems()
        return true
    }

    fun navigateToPathComponent(index: Int) {
        val components = _uiState.value.pathComponents
        val candidate = if (index <= 0) "" else {
            components.drop(1).take(index).joinToString("/")
        }
        val initial = initialRelativePath
        relativePath = when {
            initial.isEmpty() -> candidate
            candidate == initial || candidate.startsWith("$initial/") -> candidate
            else -> initial
        }
        updatePathComponents()
        loadItems()
    }

    fun deleteItem(item: FileItem) {
        val action = FileBrowserDeletePolicy.action(
            linuxPath = FileBrowserDeletePolicy.rowLinuxPath(_uiState.value.currentLinuxPath, item.name),
            isSymlink = Files.isSymbolicLink(item.file.toPath()),
        )
        if (action == FileBrowserDeletePolicy.Action.REFUSE_MOUNT_POINT) {
            _uiState.value = _uiState.value.copy(refusedMountPointName = item.name)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                FileBrowserDeletePolicy.delete(item.file, action)
                loadItems()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message)
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, refusedMountPointName = null)
    }

    fun setSort(
        key: FileSortKey = _uiState.value.sortKey,
        ascending: Boolean = _uiState.value.sortAscending,
        foldersFirst: Boolean = _uiState.value.foldersFirst,
    ) {
        val newState = _uiState.value.copy(
            sortKey = key,
            sortAscending = ascending,
            foldersFirst = foldersFirst,
        )
        val sorted = applySort(rawItems, newState)
        _uiState.value = newState.copy(items = sorted, isEmpty = sorted.isEmpty())
    }

    private fun applySort(
        items: List<FileItem>,
        state: FileBrowserUiState,
    ): List<FileItem> {
        val nameAsc = Comparator<FileItem> { a, b ->
            a.name.compareTo(b.name, ignoreCase = true)
        }
        val keyComparator: Comparator<FileItem> = when (state.sortKey) {
            FileSortKey.NAME -> nameAsc
            FileSortKey.MODIFIED -> Comparator { a, b ->
                val cmp = a.modifiedMs.compareTo(b.modifiedMs)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
            FileSortKey.SIZE -> Comparator { a, b ->
                val cmp = a.size.compareTo(b.size)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
            FileSortKey.KIND -> Comparator { a, b ->
                val ea = if (a.isDirectory) "" else a.file.extension.lowercase()
                val eb = if (b.isDirectory) "" else b.file.extension.lowercase()
                val cmp = ea.compareTo(eb)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
        }
        val directional = if (state.sortAscending) keyComparator else keyComparator.reversed()
        val final: Comparator<FileItem> = if (state.foldersFirst) {
            Comparator<FileItem> { a, b ->
                when {
                    a.isDirectory && !b.isDirectory -> -1
                    !a.isDirectory && b.isDirectory -> 1
                    else -> 0
                }
            }.then(directional)
        } else {
            directional
        }
        return items.sortedWith(final)
    }

    private fun loadItems() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val hostPath = currentHostPath
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolvedPath = try {
                    hostPath.toPath().toRealPath().toFile()
                } catch (_: Exception) {
                    hostPath
                }

                val showHidden = _uiState.value.showHidden
                val files = resolvedPath.listFiles()
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.mapNotNull { FileItem.from(it) }
                    ?: emptyList()

                rawItems = files
                val sorted = applySort(files, _uiState.value)
                _uiState.value = _uiState.value.copy(
                    items = sorted,
                    isLoading = false,
                    isEmpty = sorted.isEmpty(),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    items = emptyList(),
                    isLoading = false,
                    isEmpty = true,
                    errorMessage = e.message,
                )
            }
        }
    }

    private fun updatePathComponents() {
        val components = mutableListOf(rootLabel)
        if (relativePath.isNotEmpty()) {
            components += relativePath.split("/").filter { it.isNotEmpty() }
        }

        _uiState.value = _uiState.value.copy(
            pathComponents = components,
            canGoBack = relativePath != initialRelativePath,
            currentPath = currentHostPath.absolutePath,
            currentLinuxPath = (displayLinuxPrefix ?: linuxRootPath)?.let { root ->
                if (relativePath.isEmpty()) root.trimEnd('/')
                else "${root.trimEnd('/')}/$relativePath"
            },
        )
    }

    companion object {
        val dateFormatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

        const val PREFS_NAME = "file_browser_prefs"
        const val PREF_KEY_SHOW_HIDDEN = "file_browser_show_hidden"

        fun formatDate(timestamp: Long): String {
            return if (timestamp > 0) dateFormatter.format(Date(timestamp)) else "—"
        }
    }
}
