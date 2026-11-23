package com.openminis.app.ui.chat


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun ChatViewModel.updateMentionMenuState(text: String, caret: Int) {
    if (_showSlashMenu.value) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val safeCaret = caret.coerceIn(0, text.length)
    var anchor = -1
    var i = safeCaret
    while (i > 0) {
        val prev = i - 1
        val ch = text[prev]
        if (ch.isWhitespace()) break
        if (ch == '@' || ch == '＠') {
            if (prev == 0 || text[prev - 1].isWhitespace()) {
                anchor = prev
            }
            break
        }
        i = prev
    }
    if (anchor < 0) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val filter = text.substring(anchor + 1, safeCaret)
    _mentionAnchor.value = anchor
    _mentionFilter.value = filter
    if (!_showMentionMenu.value) {
        _showMentionMenu.value = true
        _mentionSelectedIndex.value = 0
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) fileMentionIndex.refreshIfNeeded(sid)
        Log.i(ChatViewModel.TAG, "mention menu open anchor=$anchor filter=\"$filter\"")
    } else {
        val current = _mentionSelectedIndex.value
        if (current < 0) _mentionSelectedIndex.value = 0
    }
}

internal fun ChatViewModel.dismissMentionMenu() {
    if (!_showMentionMenu.value) return
    _showMentionMenu.value = false
    _mentionFilter.value = ""
    _mentionAnchor.value = -1
    _mentionSelectedIndex.value = -1
}

internal fun ChatViewModel.mentionMenuUp() {
    val count = mentionEntries.value.size
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx <= 0) count - 1 else idx - 1
}

internal fun ChatViewModel.mentionMenuDown() {
    val count = mentionEntries.value.size
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx >= count - 1) 0 else idx + 1
}

internal fun ChatViewModel.executeSelectedMention(
    currentText: String,
    currentCaret: Int,
): Pair<String, Int>? {
    val entries = mentionEntries.value
    if (entries.isEmpty()) return null
    val idx = _mentionSelectedIndex.value.let {
        if (it in entries.indices) it else 0
    }
    return selectMention(entries[idx], currentText, currentCaret)
}

internal fun ChatViewModel.selectMention(
    entry: FileMentionIndex.Entry,
    currentText: String,
    currentCaret: Int,
): Pair<String, Int> {
    val anchor = _mentionAnchor.value
    if (anchor < 0 || anchor > currentText.length) {
        dismissMentionMenu()
        return currentText to currentCaret
    }
    var endOffset = anchor + 1
    while (endOffset < currentText.length && !currentText[endOffset].isWhitespace()) {
        endOffset++
    }
    val replacement = "@${entry.linuxPath} "
    val newText = currentText.substring(0, anchor) +
        replacement +
        currentText.substring(endOffset)
    val newCaret = anchor + replacement.length
    dismissMentionMenu()
    return newText to newCaret
}
