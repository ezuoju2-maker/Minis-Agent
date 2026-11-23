package com.openminis.app.terminal

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MinisOpenUrlBroker {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    val pendingUrl: StateFlow<Uri?> = _pendingUrl.asStateFlow()

    private val _toolSheetVisible = MutableStateFlow(false)
    val toolSheetVisible: StateFlow<Boolean> = _toolSheetVisible.asStateFlow()

    private val _terminalVisible = MutableStateFlow(false)
    val terminalVisible: StateFlow<Boolean> = _terminalVisible.asStateFlow()

    fun offer(uri: Uri) { _pendingUrl.value = uri }

    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (!isSupportedScheme(parsed.scheme)) return
        _pendingUrl.value = parsed
    }

    fun consume() { _pendingUrl.value = null }

    fun setToolSheetVisible(visible: Boolean) { _toolSheetVisible.value = visible }
    fun setTerminalVisible(visible: Boolean) { _terminalVisible.value = visible }

    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }

    fun isWebScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about"
    }
}
