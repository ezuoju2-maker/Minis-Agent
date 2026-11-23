package com.openminis.app.ui.settings.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.backup.remote.RcloneBackendCatalog
import com.openminis.app.backup.remote.RcloneBridge
import com.openminis.app.backup.remote.RcloneBrowser
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RcloneDestinationsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RcloneRemoteStore(app)

    private val _remotes = MutableStateFlow(store.remotes)
    val remotes: StateFlow<List<RcloneRemoteStore.Remote>> = _remotes.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _browse = MutableStateFlow<BrowseState?>(null)
    val browse: StateFlow<BrowseState?> = _browse.asStateFlow()

    data class BrowseState(
        val remote: RcloneRemoteStore.Remote,
        val path: String,
        val entries: List<RcloneBrowser.Entry>,
        val existing: Boolean = false,
    )

    private val _certificateRejected = MutableStateFlow(false)
    val certificateRejected: StateFlow<Boolean> = _certificateRejected.asStateFlow()

    fun refresh() { _remotes.value = store.remotes }
    fun clearError() { _error.value = null }

    fun clearCertificateRejection() { _certificateRejected.value = false }

    fun setEnabled(name: String, on: Boolean) {
        store.setEnabled(name, on); refresh()
    }

    fun remove(name: String) {
        store.remove(name); refresh()
    }


    fun hasSecret(name: String): Boolean = store.hasSecret(name)

    fun rename(name: String, newName: String, onDone: (RcloneRemoteStore.Remote) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val r = store.update(name = name, newName = newName)
                    if (r.name != name) {
                        com.openminis.app.backup.BackupHistory.get(getApplication())
                            .renameDestination(name, r.name)
                        if (!RcloneRemoteStore.isLocalFolder(r.backend)) {
                            store.dropRcloneSection(name)
                            store.syncToRclone()
                        }
                    }
                    r
                }
                refresh()
                onDone(updated)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't rename the destination."
            } finally {
                _busy.value = false
            }
        }
    }

    fun saveConnection(
        name: String,
        values: Map<String, String>,
        secret: RcloneRemoteStore.SecretEdit,
        allowInsecureTLS: Boolean,
        onDone: (RcloneRemoteStore.Remote) -> Unit,
    ) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val r = store.update(
                        name = name,
                        newParams = values,
                        secret = secret,
                        allowInsecureTLS = allowInsecureTLS,
                        verify = { candidate, candidateSecret ->
                            try {
                                store.probeConnection(candidate, candidateSecret)
                            } catch (e: Exception) {
                                throw ProbeFailed(e)
                            }
                        },
                    )
                    store.syncToRclone()
                    r
                }
                _certificateRejected.value = false
                refresh()
                onDone(updated)
            } catch (e: ProbeFailed) {
                val msg = e.cause?.message ?: "Could not connect to the server."
                AppLogger.error(TAG, "[Rclone] edit of '$name' not saved, connection failed: $msg")
                _error.value = getApplication<Application>()
                    .getString(com.openminis.app.R.string.backup_dest_edit_failed, msg)
                if (!allowInsecureTLS && RcloneRemoteStore.looksLikeCertificateFailure(msg)) {
                    _certificateRejected.value = true
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't save the connection."
            } finally {
                _busy.value = false
            }
        }
    }

    private class ProbeFailed(cause: Exception) : Exception(cause.message, cause)

    fun testConnection(name: String, onResult: (ok: Boolean, message: String?) -> Unit) {
        val r = store.remote(name) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.syncToRclone()
                    RcloneBrowser.listDirectories(r, r.listRoot)
                }
            }
            onResult(
                result.isSuccess,
                result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            )
        }
    }

    fun browseExisting(name: String) {
        val r = store.remote(name) ?: return
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    store.syncToRclone()
                    RcloneBrowser.listDirectories(r, r.listRoot)
                }
                _browse.value = BrowseState(r, r.listRoot, entries, existing = true)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not open that folder."
            } finally {
                _busy.value = false
            }
        }
    }

    fun applyFolder(path: String, onDone: (RcloneRemoteStore.Remote) -> Unit) {
        val b = _browse.value ?: return
        _busy.value = true
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    store.update(name = b.remote.name, newPath = path)
                }
                refresh()
                _browse.value = null
                onDone(updated)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save the folder."
            } finally {
                _busy.value = false
            }
        }
    }

    fun addLocalFolder(treeUri: String, displayPath: String): String? {
        val pretty = prettyTreePath(displayPath)
        val name = uniqueLocalName(pretty)
        return try {
            store.addLocalFolder(name = name, treeUri = treeUri, displayPath = pretty)
            refresh()
            name
        } catch (e: Exception) {
            _error.value = e.message ?: "Couldn't add that folder."
            null
        }
    }

    private fun prettyTreePath(treeDocumentId: String): String =
        treeDocumentId.substringAfter(':', treeDocumentId).trim('/')
            .ifBlank { treeDocumentId }

    private fun uniqueLocalName(displayPath: String): String {
        val base = displayPath.trimEnd('/').substringAfterLast('/')
            .ifBlank { "Folder" }
        val taken = store.remotes.map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    fun connectAndBrowse(
        backend: String,
        displayName: String,
        values: Map<String, String>,
        startPath: String,
        allowInsecureTLS: Boolean = false,
        onConnected: (() -> Unit)? = null,
    ) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            var connected = false
            try {
                val candidate = withContext(Dispatchers.IO) {
                    val nonSecret = values.filterKeys { key ->
                        RcloneBackendCatalog.backend(backend)?.fields
                            ?.firstOrNull { it.key == key }?.isSecret != true
                    }
                    val secretKeyName = if (backend == "s3") "secret_access_key" else "pass"
                    val secret = values[secretKeyName]
                    RcloneBridge.setInsecureTLS(
                        allowInsecureTLS || store.remotes.any { it.allowInsecureTLS }
                    )
                    registerEphemeral(displayName, backend, nonSecret, secret, allowInsecureTLS)
                    val remote = RcloneRemoteStore.Remote(
                        name = displayName, backend = backend,
                        params = nonSecret, path = startPath,
                        allowInsecureTLS = allowInsecureTLS,
                    )
                    val entries = RcloneBrowser.listDirectories(remote, startPath)
                    BrowseState(remote, startPath, entries)
                }
                _browse.value = candidate
                _certificateRejected.value = false
                connected = true
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Rclone] connect failed: ${e.message}")
                _error.value = e.message ?: "Could not connect to the server."
                if (!allowInsecureTLS &&
                    RcloneRemoteStore.looksLikeCertificateFailure(e.message)
                ) {
                    _certificateRejected.value = true
                }
            } finally {
                _busy.value = false
            }
            if (connected) onConnected?.invoke()
        }
    }

    fun navigateTo(path: String) {
        val b = _browse.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    RcloneBrowser.listDirectories(b.remote, path)
                }
                _browse.value = b.copy(path = path, entries = entries)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not open that folder."
            } finally {
                _busy.value = false
            }
        }
    }

    fun createFolder(name: String) {
        val b = _browse.value ?: return
        if (_busy.value || name.isBlank()) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val newPath = withContext(Dispatchers.IO) {
                    RcloneBrowser.createFolder(b.remote, b.path, name)
                }
                val entries = withContext(Dispatchers.IO) {
                    RcloneBrowser.listDirectories(b.remote, b.path)
                }
                _browse.value = b.copy(entries = entries)
                navigateTo(newPath)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not create the folder."
            } finally {
                _busy.value = false
            }
        }
    }

    fun savePending(path: String, onDone: () -> Unit) {
        val b = _browse.value ?: return
        _busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val secretKeyName = if (b.remote.backend == "s3") "secret_access_key" else "pass"
                    store.add(
                        name = b.remote.name,
                        backend = b.remote.backend,
                        params = b.remote.params,
                        secret = pendingSecret,
                        path = path,
                        allowInsecureTLS = b.remote.allowInsecureTLS,
                    )
                    store.syncToRclone()
                }
                refresh()
                _browse.value = null
                pendingSecret = null
                onDone()
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save the destination."
            } finally {
                _busy.value = false
            }
        }
    }

    fun saveConnected(onDone: (String) -> Unit) {
        val b = _browse.value ?: return
        savePending(b.path) { onDone(b.remote.name) }
    }

    fun cancelPending() {
        _browse.value = null
        pendingSecret = null
    }

    @Volatile private var pendingSecret: String? = null

    private fun registerEphemeral(
        name: String,
        backend: String,
        nonSecret: Map<String, String>,
        secret: String?,
        allowInsecureTLS: Boolean = false,
    ) {
        pendingSecret = secret
        val params = RcloneRemoteStore.buildConfigParams(
            backend = backend,
            params = nonSecret,
            secret = secret,
            obscure = { clear ->
                runCatching {
                    RcloneBridge.rpc("core/obscure", mapOf("clear" to clear))
                        .optString("obscured").takeIf { it.isNotEmpty() }
                }.getOrNull()
            },
            allowInsecureTLS = allowInsecureTLS,
        )
        RcloneBridge.rpc(
            "config/create",
            mapOf(
                "name" to name, "type" to backend, "parameters" to params,
                "opt" to mapOf("nonInteractive" to true),
            ),
        )
    }

    companion object {
        private const val TAG = "Rclone"
    }
}
