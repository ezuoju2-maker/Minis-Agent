package com.openminis.app.ui.sessions

import com.openminis.app.R
import kotlinx.coroutines.flow.first
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(FlowPreview::class)
class SessionListViewModel(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "SessionListVM"


        private const val GROUP_SUGGEST_SYSTEM_PROMPT =
            "You organize chat sessions into folders. Respond with a single valid JSON object only."

        internal fun parseGroupSuggestion(
            text: String,
            folders: List<FolderEntity>,
        ): GroupSuggestion? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = try {
                JSONObject(text.substring(start, end + 1))
            } catch (_: Exception) {
                return null
            }
            val decision = json.optString("decision").lowercase()
            val folderName = json.optString("folder").takeIf { it.isNotBlank() }
            if (decision == "merge" && folderName != null) {
                val match = folders
                    .filter { it.name.trim().equals(folderName.trim(), ignoreCase = true) }
                    .maxByOrNull { it.updatedAt }
                if (match != null) return GroupSuggestion.Merge(match.id, match.name)
            }
            val newName = (json.optString("name").takeIf { it.isNotBlank() } ?: folderName)?.trim()
            if (newName.isNullOrEmpty()) return null
            val desc = json.optString("description").trim()
                .takeIf { it.isNotEmpty() }?.take(FolderEntity.DESC_MAX_CHARS)
            return GroupSuggestion.Create(newName, desc)
        }

        fun factory(
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SessionListViewModel(
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                ) as T
            }
        }
    }

    private val _allSessions = MutableStateFlow<List<ChatSessionEntity>>(emptyList())

    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val isSearchActive = MutableStateFlow(false)
    val searchResults = MutableStateFlow<List<ChatSessionEntity>>(emptyList())

    val isSearching = MutableStateFlow(false)

    val searchSnippets = MutableStateFlow<Map<String, String>>(emptyMap())

    val searchResultsReady = MutableStateFlow(false)

    val searchMatchedFolderIds = MutableStateFlow<Set<String>>(emptySet())

    val searchFolderPages = MutableStateFlow<Map<String, Int>>(emptyMap())

    val searchCollapsedFolderIds = MutableStateFlow<Set<String>>(emptySet())

    fun toggleSearchFolderCollapsed(folderId: String) {
        searchCollapsedFolderIds.update { if (folderId in it) it - folderId else it + folderId }
    }

    fun reopenSearchFolder(folderId: String) {
        searchCollapsedFolderIds.update { it - folderId }
    }

    fun showMoreInSearchFolder(folderId: String, pagesShown: Int) {
        searchFolderPages.update { FolderPages.afterSearchShowMore(it, folderId, pagesShown) }
    }

    val displayedSessions: StateFlow<List<ChatSessionEntity>> = combine(
        _allSessions, searchResults, searchQuery, isSearchActive
    ) { all, results, q, active ->
        if (active && q.isNotBlank()) results else all
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    val folders = MutableStateFlow<List<FolderEntity>>(emptyList())

    val expandedFolderId = MutableStateFlow<String?>(null)

    val folderPages = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun showMoreInFolder(folderId: String, pagesShown: Int) {
        folderPages.update { FolderPages.afterShowMore(it, expandedFolderId.value, folderId, pagesShown) }
    }

    fun keepFolderPages(folderId: String, pages: Int) {
        folderPages.update { FolderPages.afterReveal(it, expandedFolderId.value, folderId, pages) }
    }

    init {
        viewModelScope.launch {
            expandedFolderId.collect { open ->
                folderPages.update { FolderPages.afterOpenChanged(it, open) }
            }
        }
        context.getSharedPreferences("session_list_ui", Context.MODE_PRIVATE)
            .edit().remove("collapsedFolderIds").remove("lastExpandedFolderId").apply()
    }

    private fun expandOnly(folderId: String) {
        expandedFolderId.value = folderId
    }

    val groupPickerRequest = MutableStateFlow<GroupPickerRequest?>(null)

    data class GroupPickerRequest(
        val sessionIds: List<String>,
        val anyFiled: Boolean,
        val fromMultiSelect: Boolean,
    )

    sealed interface GroupSuggestion {
        data class Merge(val folderId: String, val folderName: String) : GroupSuggestion

        data class Create(val name: String, val description: String?) : GroupSuggestion
    }

    val groupSuggesting = MutableStateFlow(false)

    val groupSuggestion = MutableStateFlow<GroupSuggestion?>(null)

    val groupSuggestFailed = MutableStateFlow(false)

    val isSelecting = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val regeneratingIds = MutableStateFlow<Set<String>>(emptySet())

    val newTopSessionEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private var knownSessionIds: Set<String> = emptySet()
    private var newTopBaselineSeeded = false

    init {
        viewModelScope.launch {
            if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
                android.util.Log.w(
                    TAG,
                    "SessionListVM init: safe-mode active, deferring observeSessions",
                )
                _isInitialLoadComplete.value = true
                val started = kotlinx.coroutines.CompletableDeferred<Unit>()
                val unsub = com.openminis.app.crash.CrashFrequencyDetector
                    .registerSafeModeClearedListener {
                        if (!started.isCompleted) started.complete(Unit)
                    }
                started.await()
                runCatching { unsub() }
            }
            chatRepository.observeSessions().collect { rows ->
                val it = rows.filter { !it.isChild }
                _allSessions.value = it
                if (!_isInitialLoadComplete.value) _isInitialLoadComplete.value = true
                detectNewTopSession(it)
            }
        }
        viewModelScope.launch {
            chatRepository.observeFolders().collect { folders.value = it }
        }
        viewModelScope.launch {
            combine(searchQuery, isSearchActive) { q, active -> q to active }
                .distinctUntilChanged()
                .onEach { (q, active) ->
                    isSearching.value = active && q.isNotBlank()
                }
                .debounce(300)
                .collectLatest { (q, active) ->
                    if (active && q.isNotBlank()) {
                        val started = System.nanoTime()
                        val hits = withContext(Dispatchers.IO) {
                            try {
                                chatRepository.searchSessionsWithHits(q)
                            } catch (e: android.database.sqlite.SQLiteException) {
                                com.openminis.app.logging.AppLogger.error(
                                    "SessionList", "[Search] query failed: ${e.javaClass.simpleName}: ${e.message}",
                                )
                                emptyList()
                            }
                        }
                        com.openminis.app.logging.AppLogger.info(
                            "SessionList",
                            "[Search] terms=${com.openminis.app.data.repository.SessionSearch.searchTerms(q).size} " +
                                "results=${hits.size} ms=${(System.nanoTime() - started) / 1_000_000}",
                        )
                        val folderHits = SearchFolders.matchedFolderIds(folders.value, q)
                        searchResults.value = SearchFolders.withFolderMembers(hits.map { it.session }, _allSessions.value, folderHits)
                        searchSnippets.value = hits.mapNotNull { h -> h.snippet?.let { h.session.id to it } }.toMap()
                        searchMatchedFolderIds.value = folderHits
                        searchFolderPages.value = emptyMap()
                        searchCollapsedFolderIds.value = emptySet()
                        searchResultsReady.value = true
                    } else {
                        searchResults.value = emptyList()
                        searchSnippets.value = emptyMap()
                        searchMatchedFolderIds.value = emptySet()
                        searchFolderPages.value = emptyMap()
                        searchCollapsedFolderIds.value = emptySet()
                        searchResultsReady.value = false
                    }
                    isSearching.value = false
                }
        }
    }

    fun toggleSelect(id: String) {
        selectedIds.value = selectedIds.value.toMutableSet().also {
            if (id in it) it.remove(id) else it.add(id)
        }
    }

    fun enterSelection(id: String) {
        selectedIds.value = selectedIds.value + id
        isSelecting.value = true
    }

    fun toggleSelectAll() {
        val shown = displayedSessions.value.map { it.id }.toSet()
        selectedIds.value = if (allShownSelected(selectedIds.value, shown)) emptySet() else shown
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        isSelecting.value = false
    }

    fun deleteSelected() {
        val ids = selectedIds.value.toList()
        viewModelScope.launch {
            ids.forEach { SessionDeleter.deleteTree(context, chatRepository, it, "list-multi") }
        }
        clearSelection()
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            SessionDeleter.deleteTree(context, chatRepository, id, "list-single")
        }
    }


    private fun isFiled(session: ChatSessionEntity?): Boolean {
        val fid = session?.folderId ?: return false
        return folders.value.any { it.id == fid }
    }

    fun requestGroupPicker(sessionId: String) {
        val filed = isFiled(_allSessions.value.firstOrNull { it.id == sessionId })
        groupPickerRequest.value = GroupPickerRequest(
            sessionIds = listOf(sessionId),
            anyFiled = filed,
            fromMultiSelect = false,
        )
    }

    fun requestGroupPickerForSelection() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        val anyFiled = _allSessions.value.any { it.id in ids && isFiled(it) }
        groupPickerRequest.value = GroupPickerRequest(
            sessionIds = ids,
            anyFiled = anyFiled,
            fromMultiSelect = true,
        )
    }

    fun dismissGroupPicker() {
        val wasMultiSelect = groupPickerRequest.value?.fromMultiSelect == true
        groupPickerRequest.value = null
        groupSuggestion.value = null
        groupSuggestFailed.value = false
        groupSuggesting.value = false
        if (wasMultiSelect) clearSelection()
    }

    fun suggestGroup() {
        val request = groupPickerRequest.value ?: return
        if (groupSuggesting.value) return
        val sessionIds = request.sessionIds
        if (sessionIds.isEmpty()) {
            AppLogger.error(TAG, "[GroupSuggest] FAILED reason=no-sessions-selected")
            groupSuggestFailed.value = true
            return
        }
        groupSuggesting.value = true
        groupSuggestFailed.value = false
        groupSuggestion.value = null
        AppLogger.info(TAG, "[GroupSuggest] START sessions=${sessionIds.size}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = runGroupSuggestion(sessionIds)
                withContext(Dispatchers.Main) { groupSuggestion.value = result }
            } catch (e: Exception) {
                AppLogger.error(TAG, "[GroupSuggest] FAILED reason=exception error=${e.message}")
                withContext(Dispatchers.Main) { groupSuggestFailed.value = true }
            } finally {
                withContext(Dispatchers.Main) { groupSuggesting.value = false }
            }
        }
    }

    fun clearGroupSuggestion() {
        groupSuggestion.value = null
        groupSuggestFailed.value = false
    }

    private suspend fun runGroupSuggestion(sessionIds: List<String>): GroupSuggestion {
        val sorted = sessionIds.sorted()

        val titleEligible = providerRepository.allVisibleEntries().filter { entry ->
            val outs = entry.model.outputModalities
            val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
            val idLower = entry.model.id.lowercase()
            val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
                .any { idLower.contains(it) }
            outputsText && !nonChatId
        }
        val subEntry = providerRepository.resolveTitleSubEntry()
            ?.takeIf { sub -> titleEligible.any { it == sub } }
        val anchorPrimary = sorted.firstNotNullOfOrNull { sid ->
            val modelId = chatRepository.getSession(sid)?.modelId ?: return@firstNotNullOfOrNull null
            titleEligible.firstOrNull { it.model.id == modelId }
        }
        val candidates = (listOfNotNull(subEntry, anchorPrimary) +
            titleEligible.filter { it != subEntry && it != anchorPrimary })
        if (candidates.isEmpty()) {
            AppLogger.error(
                TAG,
                "[GroupSuggest] FAILED reason=no-sub-model-available " +
                    "(no enabled+credentialed+title-eligible entry for ${sessionIds.size} session(s))",
            )
            throw IllegalStateException("No sub model available")
        }

        val folders = chatRepository.listFolders()
        val seenNames = mutableSetOf<String>()
        val folderLines = mutableListOf<String>()
        for (f in folders) {
            if (!seenNames.add(f.name.lowercase())) continue
            val memberTitles = chatRepository.sessionIdsInFolder(f.id).take(3)
                .mapNotNull { chatRepository.getSession(it)?.title }
            val descPart = f.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            folderLines += "- \"${f.name}\"$descPart: ${memberTitles.joinToString(" / ")}"
        }

        val sessionLines = sorted.take(20).mapNotNull { sid ->
            chatRepository.getSession(sid)?.let { s ->
                "- ${s.title ?: "Untitled"} [${s.category ?: "other"}]"
            }
        }
        if (sessionLines.isEmpty()) {
            AppLogger.error(
                TAG,
                "[GroupSuggest] FAILED reason=no-sessions-found " +
                    "(${sessionIds.size} id(s) selected, none resolved in the store)",
            )
            throw IllegalStateException("No sessions found")
        }

        val foldersBlock = if (folderLines.isEmpty()) "The user has no folders yet."
        else "Existing folders with sample member titles:\n${folderLines.joinToString("\n")}"
        val prompt = buildString {
            append("The user selected these chat sessions to file into a folder:\n")
            append(sessionLines.joinToString("\n"))
            append("\n\n")
            append(foldersBlock)
            append("\n\n")
            append("Decide: merge them into ONE existing group (only if they clearly fit it), ")
            append("or propose ONE new group name (2-8 characters preferred, in the same language as the session titles). ")
            append("For a new group also write \"description\": one sentence (under 100 characters, same language as the name) ")
            append("describing what belongs in it — it will guide future automatic grouping.\n\n")
            append("You MUST respond with valid JSON only. Examples:\n")
            append("{\"decision\": \"merge\", \"folder\": \"Work\"}\n")
            append("{\"decision\": \"create\", \"name\": \"Trip Planning\", \"description\": \"Flights, hotels and itineraries for upcoming trips\"}")
        }

        var lastError: Exception? = null
        for (entry in candidates) {
            val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
            val apiKey = providerRepository.requestCredential(instance) ?: continue
            val provider = try {
                ProviderFactory.create(
                    instance, apiKey, entry.model, context,
                    sessionId = ProviderFactory.oneOffSessionId(), overrides = entry.overrides,
                )
            } catch (e: Exception) {
                Log.w(TAG, "GroupSuggest provider creation failed for ${entry.model.displayName}: ${e.message}")
                continue
            }
            try {
                val maxTokens = if (entry.model.supportsReasoning == true) 2048 else 256
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = GROUP_SUGGEST_SYSTEM_PROMPT,
                    maxTokens = maxTokens,
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                )
                val parsed = parseGroupSuggestion(response.text, folders)
                if (parsed != null) {
                    AppLogger.info(TAG, "[GroupSuggest] OK model=${entry.model.id} result=$parsed")
                    return parsed
                }
                Log.w(
                    TAG,
                    "GroupSuggest empty/unparseable from ${entry.model.displayName}: " +
                        "stopReason=${response.stopReason} raw=\"${response.text.take(160)}\"",
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "GroupSuggest via ${entry.model.displayName} failed: ${e.message}")
                continue
            }
        }
        AppLogger.error(TAG, "[GroupSuggest] FAILED reason=all-candidates-exhausted last=${lastError?.message}")
        throw lastError ?: IllegalStateException("Unparseable suggestion")
    }

    fun applyGroupChoice(choice: GroupChoice) {
        val request = groupPickerRequest.value ?: return
        viewModelScope.launch {
            when (choice) {
                is GroupChoice.Existing ->
                    chatRepository.setFolderForSessions(choice.folderId, request.sessionIds)
                is GroupChoice.Create -> {
                    val suggested = groupSuggestion.value as? GroupSuggestion.Create
                    val fromAi = suggested != null &&
                        suggested.name.trim().equals(choice.name.trim(), ignoreCase = true)
                    val folder = chatRepository.createFolder(
                        choice.name,
                        choice.description,
                        origin = if (fromAi) FolderEntity.ORIGIN_AI else FolderEntity.ORIGIN_MANUAL,
                    )
                    chatRepository.setFolderForSessions(folder.id, request.sessionIds)
                    expandOnly(folder.id)
                }
                GroupChoice.RemoveFromGroup ->
                    chatRepository.setFolderForSessions(null, request.sessionIds)
            }
            AppLogger.info(
                TAG,
                "[Group] applied ${choice::class.simpleName} to ${request.sessionIds.size} session(s)",
            )
            dismissGroupPicker()
        }
    }

    fun toggleFolderCollapsed(folderId: String) {
        expandedFolderId.value = if (expandedFolderId.value == folderId) null else folderId
    }

    fun deleteFolderWithSessions(folderId: String) {
        viewModelScope.launch {
            val memberIds = chatRepository.sessionIdsInFolder(folderId)
            for (id in memberIds) {
                SessionDeleter.deleteTree(context, chatRepository, id, "folder-delete")
            }
            chatRepository.dissolveFolder(folderId)
            expandedFolderId.compareAndSet(folderId, null)
            AppLogger.info(
                TAG,
                "[Group] deleted folder ${folderId.take(8)} with ${memberIds.size} session(s)",
            )
        }
    }

    fun toggleFolderPin(folderId: String) {
        viewModelScope.launch { chatRepository.toggleFolderPin(folderId) }
    }

    fun renameFolder(folderId: String, name: String, description: String?) {
        viewModelScope.launch { chatRepository.renameFolder(folderId, name, description) }
    }

    fun dissolveFolder(folderId: String) {
        viewModelScope.launch {
            val freed = chatRepository.dissolveFolder(folderId)
            expandedFolderId.compareAndSet(folderId, null)
            AppLogger.info(TAG, "[Group] dissolved ${folderId.take(8)}, freed ${freed.size} session(s)")
        }
    }

    val folderLastActivity: StateFlow<Map<String, Long>> =
        _allSessions.map { sessions ->
            buildMap {
                for (s in sessions) {
                    val fid = s.folderId ?: continue
                    if (s.updatedAt > (get(fid) ?: Long.MIN_VALUE)) put(fid, s.updatedAt)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val folderMemberCounts: StateFlow<Map<String, Int>> =
        combine(_allSessions, folders) { sessions, _ ->
            sessions.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun togglePin(id: String) {
        viewModelScope.launch {
            val session = chatRepository.getSession(id) ?: return@launch
            val newPinnedAt = if (session.pinnedAt != null) null else System.currentTimeMillis()
            chatRepository.dao.updatePinnedAt(id, newPinnedAt)
        }
    }

    fun updateTitleAndCategory(id: String, title: String, category: String?) {
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(id, title, category)
        }
    }

    fun regenerateTitle(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                regeneratingIds.value = regeneratingIds.value + id
            }
            try {
                generateTitleFromStore(id, origin = "manual")
            } finally {
                withContext(Dispatchers.Main) {
                    regeneratingIds.value = regeneratingIds.value - id
                }
            }
        }
    }

    private suspend fun generateTitleFromStore(id: String, origin: String): Boolean =
        titleRegenerator.regenerate(id, origin)

    private val titleRegenerator by lazy { SessionTitleRegenerator(chatRepository, providerRepository, context) }


    private val reservedDuplicateTitles = HashSet<String>()

    fun duplicateSession(id: String) {
        viewModelScope.launch {
            val session = chatRepository.getSession(id) ?: return@launch
            val existingTitles = chatRepository.observeSessions().first().mapNotNull { it.title }
            val title = SessionDuplicateTitle.make(
                sourceTitle = session.title,
                existingTitles = existingTitles + reservedDuplicateTitles,
                suffixWord = context.getString(R.string.session_duplicate_suffix),
                knownSuffixWords = allCopySuffixWords(),
                untitledTitle = context.getString(R.string.chat_title_pill_session_default),
            )
            reservedDuplicateTitles.add(title)
            val messages = try { chatRepository.loadMessages(id) } catch (e: Exception) { reservedDuplicateTitles.remove(title); throw e }
            val newSession = try {
                chatRepository.createSession(modelId = session.modelId, title = title)
            } finally {
                reservedDuplicateTitles.remove(title)
            }
            for (msg in messages) {
                chatRepository.appendMessage(
                    sessionId = newSession.id,
                    role = msg.role,
                    partsJson = msg.partsJson,
                    tokenUsage = com.openminis.app.usage.StoredUsageJson.markCopied(msg.tokenUsage),
                    reasoningContent = msg.reasoningContent,
                )
            }
        }
    }

    private fun allCopySuffixWords(): List<String> =
        listOf(java.util.Locale.ENGLISH, java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.TRADITIONAL_CHINESE).map { loc ->
            val cfg = android.content.res.Configuration(context.resources.configuration).apply { setLocale(loc) }
            context.createConfigurationContext(cfg).getString(R.string.session_duplicate_suffix)
        }

    fun createNewSession(groupId: String? = null, folderId: String? = null): String? {
        if (providerRepository.allVisibleEntries().isEmpty()) return null
        return com.openminis.app.ui.chat.ChatViewModelStore.newChatDraftId(groupId, folderId)
    }

    private fun detectNewTopSession(sessions: List<ChatSessionEntity>) {
        val topId = sessions.firstOrNull()?.id
        if (!newTopBaselineSeeded) {
            knownSessionIds = sessions.mapTo(HashSet()) { it.id }
            newTopBaselineSeeded = true
            return
        }
        val isNewTop = topId != null && topId !in knownSessionIds
        knownSessionIds = knownSessionIds + sessions.map { it.id }
        if (isNewTop) newTopSessionEvent.tryEmit(Unit)
    }

    fun hasProviders(): Boolean = providerRepository.instances.isNotEmpty()
}

internal fun allShownSelected(selected: Set<String>, shown: Set<String>): Boolean =
    shown.isNotEmpty() && selected.containsAll(shown)
