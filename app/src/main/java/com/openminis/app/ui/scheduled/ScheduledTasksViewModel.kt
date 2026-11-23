package com.openminis.app.ui.scheduled

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScheduledTasksViewModel(private val appContext: Context) : ViewModel() {

    private val manager = ScheduledTaskManager(appContext)
    private val app get() = appContext as com.openminis.app.MinisApp

    private fun ready(): Boolean =
        (appContext as? com.openminis.app.MinisApp)?.subsystemsReady() == true

    val providerRepository get() = if (ready()) app.providerRepository else null

    val tasks: StateFlow<List<ScheduledTask>> = manager.store().observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showHomeBadge: StateFlow<Boolean> = manager.store().observeShowHomeBadge()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.store().showHomeBadge())

    fun setShowHomeBadge(show: Boolean) = manager.store().setShowHomeBadge(show)


    data class SessionOption(val id: String, val title: String, val updatedAt: Long)

    data class MessageOption(val id: String, val preview: String, val createdAt: Long)

    data class ModelOption(val entryId: String, val modelId: String, val displayName: String, val providerLabel: String)

    suspend fun listSessions(limit: Int = 100): List<SessionOption> = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext emptyList()
        val childIds = app.chatRepository.dao.listSessions().filter { it.isChild }.map { it.id }.toSet()
        app.chatRepository.querySessionsMeta(
            sessionIds = null, keywords = null, limit = limit, startMs = null, endMs = null,
        ).filter { it.id !in childIds }.map {
            SessionOption(it.id, it.title?.ifBlank { null } ?: "Untitled", it.lastActive)
        }
    }

    suspend fun listUserMessages(sessionId: String): List<MessageOption> = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext emptyList()
        app.chatRepository.loadMessages(sessionId)
            .filter { it.role == "user" }
            .map { MessageOption(it.id, extractPreview(it.partsJson), it.createdAt) }
    }

    fun describeBinding(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = org.json.JSONObject(json)
            when (o.optString("type")) {
                "group" -> {
                    val gid = o.optString("groupId").takeIf { it.isNotEmpty() } ?: return@runCatching null
                    app.providerRepository.group(gid)?.name
                }
                "entry" -> {
                    val eid = o.optString("entryId").takeIf { it.isNotEmpty() } ?: return@runCatching null
                    app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.displayName
                }
                else -> null
            }
        }.getOrNull()
    }

    fun listModels(): List<ModelOption> {
        if (!ready()) return emptyList()
        return app.providerRepository.allVisibleEntries().mapNotNull { entry ->
            val instance = app.providerRepository.instance(entry.providerInstanceId) ?: return@mapNotNull null
            ModelOption(
                entryId = entry.id,
                modelId = entry.baseModel.id,
                displayName = entry.model.displayName,
                providerLabel = instance.label.ifEmpty { entry.model.provider },
            )
        }
    }

    private fun extractPreview(partsJson: String): String {
        val text = runCatching {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString()
        }.getOrNull() ?: ""
        return com.openminis.app.ui.markdown.InlineHtmlTags.stripForPreview(text)
            .trim().replace('\n', ' ').take(80).ifBlank { "(empty)" }
    }

    private val _runNowState = MutableStateFlow<RunNowState?>(null)
    val runNowState: StateFlow<RunNowState?> = _runNowState.asStateFlow()

    fun setEnabled(taskId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { manager.setEnabled(taskId, enabled) }
    }

    fun delete(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) { manager.delete(taskId) }
    }

    suspend fun get(taskId: String): ScheduledTask? = withContext(Dispatchers.IO) {
        manager.get(taskId)
    }

    suspend fun upsert(task: ScheduledTask, isNew: Boolean): ScheduledTask =
        withContext(Dispatchers.IO) {
            if (isNew) manager.create(task) else manager.update(task)
        }

    fun runNow(task: ScheduledTask) {
        viewModelScope.launch(Dispatchers.IO) {
            _runNowState.value = RunNowState(taskId = task.id, status = RunStatus.RUNNING)
            val sid = runCatching {
                com.openminis.app.scheduled.ScheduledAgentRunner.run(
                    appContext, task, waitForCompletion = false,
                )
            }.getOrNull()
            _runNowState.value = RunNowState(
                taskId = task.id,
                status = if (sid != null) RunStatus.STARTED else RunStatus.FAILED,
                sessionId = sid,
            )
        }
    }

    fun clearRunNowState() { _runNowState.value = null }

    enum class RunStatus { RUNNING, STARTED, FAILED }
    data class RunNowState(
        val taskId: String,
        val status: RunStatus,
        val sessionId: String? = null,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScheduledTasksViewModel(context.applicationContext) as T
                }
            }
    }
}
