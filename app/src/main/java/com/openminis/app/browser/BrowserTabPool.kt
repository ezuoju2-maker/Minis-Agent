package com.openminis.app.browser

import android.content.Context
import android.os.Message
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import com.openminis.app.R
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class BrowserTabPool(private val context: Context) : BrowserTabPoolRegistry.RegistryPool {

    companion object {
        internal fun startEvictionLoop(scope: CoroutineScope, pool: java.lang.ref.WeakReference<BrowserTabPool>): Job =
            scope.launch {
                while (isActive) {
                    delay(IDLE_CHECK_INTERVAL_MS)
                    val alive = withContext(Dispatchers.Main) { pool.get()?.evictIdleTabs() != null }
                    if (!alive) break
                }
            }

        private const val TAG = "BrowserTabPool"
        private const val MAX_TABS = 3

        internal fun sanitizeDownloadName(raw: String): String {
            val base = raw.replace('\\', '/').substringAfterLast('/').trim()
            if (base.isEmpty() || base == "." || base == "..") return "download"
            return base.take(200)
        }

        const val AGENT_TAB_QUOTA = 2

        const val MAX_TABS_WITH_AGENTS = 6
        private const val IDLE_CHECK_INTERVAL_MS = 60_000L
        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 15
        const val PREF_IDLE_TIMEOUT_MINUTES = "idle_timeout_minutes"
        const val MIN_IDLE_TIMEOUT_MINUTES = 1
        const val MAX_IDLE_TIMEOUT_MINUTES = 240

        const val PREF_GLOBAL_VIEWPORT_WIDTH = "browser_custom_viewport_width"
        const val PREF_GLOBAL_VIEWPORT_HEIGHT = "browser_custom_viewport_height"

        private const val TAB_SERIAL_WAIT_TIMEOUT_MS = BrowserActionGuard.TAB_SERIAL_WAIT_TIMEOUT_MS

        fun middleTruncated(name: String, head: Int = 22, tail: Int = 10): String {
            if (name.length <= head + tail + 1) return name
            return name.take(head) + "…" + name.takeLast(tail)
        }

        private const val IMPLICIT_TAB_GRACE_MS = 15_000L

        private const val IMPLICIT_TAB_WAIT_MS = 20_000L
        private const val IMPLICIT_TAB_WAIT_POLL_MS = 250L
    }

    private val tabLocks = ConcurrentHashMap<Int, Mutex>()

    private fun lockForTab(id: Int): Mutex = tabLocks.getOrPut(id) { Mutex() }

    private val rebuildNotices = ConcurrentHashMap<Int, String>()

    data class Tab(
        val id: Int,
        val manager: BrowserUseManager,
        var inUse: Boolean = false,
        var lastActivityDate: Date = Date(),
        var needsInitialBlankPage: Boolean = false,
        var inUseGraceJob: Job? = null,
    )

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow(0)
    val selectedTabId: StateFlow<Int> = _selectedTabId.asStateFlow()

    val isAgentBusy: Boolean get() = _tabs.value.any { it.inUse }

    val activeManager: BrowserUseManager?
        get() {
            val tabs = _tabs.value
            if (tabs.isEmpty()) return null
            return tabs.firstOrNull { it.id == _selectedTabId.value }?.manager ?: tabs.first().manager
        }

    private val _userAgentProfile = MutableStateFlow(UserAgentProfile.MOBILE_CHROME)
    val currentUserAgentProfile: StateFlow<UserAgentProfile> = _userAgentProfile.asStateFlow()
    private var userAgentProfile: UserAgentProfile
        get() = _userAgentProfile.value
        set(value) { _userAgentProfile.value = value }
    private var customUserAgentString: String? = null

    private var sessionId: String? = null
    private val savedURLs = mutableMapOf<Int, String>()


    private val tabOwner = mutableMapOf<Int, String>()

    private val lastTabByOwner = mutableMapOf<String, Int>()

    private val agentTabQuota = AGENT_TAB_QUOTA

    private fun isPrivileged(owner: String?): Boolean =
        owner == null || owner == sessionId

    private fun agentOwners(): Set<String> =
        tabOwner.values.toSet() - setOfNotNull(sessionId)

    fun effectiveMaxTabs(requestingOwner: String? = null): Int {
        val owners = agentOwners().toMutableSet()
        if (requestingOwner != null && !isPrivileged(requestingOwner)) owners.add(requestingOwner)
        return if (owners.isEmpty()) MAX_TABS
        else minOf(MAX_TABS + AGENT_TAB_QUOTA * owners.size, MAX_TABS_WITH_AGENTS)
    }

    internal fun mayUse(tabId: Int, owner: String?): Boolean {
        if (isPrivileged(owner)) return true
        return tabOwner[tabId] == owner
    }

    internal fun tabIdsOwnedBy(owner: String): List<Int> =
        tabOwner.filterValues { it == owner }.keys.sorted()

    private fun notYourTabError(tabId: Int, owner: String): BrowserActionResult {
        val mine = tabIdsOwnedBy(owner)
        val hint = if (mine.isEmpty()) "open one with action: new_tab"
        else "use your own tab id(s) ${mine.joinToString(", ")} or action: new_tab"
        return BrowserActionResult.error(
            "Tab $tabId belongs to another agent or to the main chat and cannot be used from here — " +
                "$hint. list_tabs shows only your tabs.",
        )
    }

    private fun preferredTab(owner: String?): Int? {
        val o = owner ?: return null
        val id = lastTabByOwner[o] ?: return null
        return if (_tabs.value.any { it.id == id }) id else null
    }

    suspend fun releaseTabs(owner: String) {
        val mine = tabIdsOwnedBy(owner)
        for (id in mine) closeTab(id)
        lastTabByOwner.remove(owner)
        if (mine.isNotEmpty()) {
            Log.i(TAG, "[agent] released ${mine.size} tab(s) of owner ${owner.take(8)}")
        }
    }

    private val _customViewportWidth = MutableStateFlow(0)
    val customViewportWidth: StateFlow<Int> = _customViewportWidth.asStateFlow()
    private val _customViewportHeight = MutableStateFlow(0)
    val customViewportHeight: StateFlow<Int> = _customViewportHeight.asStateFlow()

    private val _sessionViewportWidth = MutableStateFlow(0)
    val sessionViewportWidth: StateFlow<Int> = _sessionViewportWidth.asStateFlow()
    private val _sessionViewportHeight = MutableStateFlow(0)
    val sessionViewportHeight: StateFlow<Int> = _sessionViewportHeight.asStateFlow()

    private var nextTabId = 0

    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var evictionJob: Job? = null

    @Volatile
    private var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MINUTES * 60_000L

    init {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        syncGlobalDefaultsFromStore()

        val storedMinutes = prefs.getInt(PREF_IDLE_TIMEOUT_MINUTES, DEFAULT_IDLE_TIMEOUT_MINUTES)
        idleTimeoutMs = storedMinutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES) * 60_000L

        BrowserTabPoolRegistry.register(this)

        evictionJob = startEvictionLoop(evictionScope, java.lang.ref.WeakReference(this))
    }

    val idleTimeoutMinutes: Int
        get() = (idleTimeoutMs / 60_000L).toInt()

    fun setIdleTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES)
        idleTimeoutMs = clamped * 60_000L
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_IDLE_TIMEOUT_MINUTES, clamped).apply()
        Log.i(TAG, "Idle timeout set to $clamped min")
    }


    fun setSession(sessionId: String) {
        this.sessionId = sessionId
        loadSavedState()
    }


    data class DownloadUiState(val filename: String, val progress: Float)

    private val _activeDownload = MutableStateFlow<DownloadUiState?>(null)
    val activeDownload: StateFlow<DownloadUiState?> = _activeDownload.asStateFlow()

    enum class DownloadState { DOWNLOADING, COMPLETED, FAILED }

    data class DownloadEntry(
        val id: Long,
        val filename: String,
        val destination: File?,
        val bytesDone: Long,
        val totalBytes: Long,
        val state: DownloadState,
        val failureReason: String? = null,
        val startedAt: Long,
        val seen: Boolean = false,
    )

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val nextDownloadId = java.util.concurrent.atomic.AtomicLong(1)

    var onDownloadEvent: ((String) -> Unit)? = null

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun registerDownload(dest: File, totalBytes: Long): Long {
        val id = nextDownloadId.getAndIncrement()
        val entry = DownloadEntry(
            id = id,
            filename = dest.name,
            destination = dest,
            bytesDone = 0L,
            totalBytes = totalBytes,
            state = DownloadState.DOWNLOADING,
            startedAt = System.currentTimeMillis(),
        )
        _downloads.value = listOf(entry) + _downloads.value
        _activeDownload.value = DownloadUiState(dest.name, if (totalBytes > 0) 0f else -1f)
        return id
    }

    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.value = _downloads.value.map { if (it.id == id) transform(it) else it }
    }

    private fun updateDownloadProgress(id: Long, name: String, copied: Long, total: Long) {
        updateDownloadEntry(id) { it.copy(bytesDone = copied, totalBytes = total) }
        _activeDownload.value = DownloadUiState(
            name,
            if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f,
        )
    }

    private fun settleDownload(id: Long, state: DownloadState, reason: String? = null, bytes: Long? = null) {
        downloadJobs.remove(id)
        updateDownloadEntry(id) {
            it.copy(
                state = state,
                failureReason = reason,
                bytesDone = bytes ?: it.bytesDone,
            )
        }
        _activeDownload.value = null
    }

    fun cancelDownload(id: Long) {
        downloadJobs[id]?.cancel()
    }

    fun downloadBadgeCount(entries: List<DownloadEntry>): Int =
        entries.count { it.state == DownloadState.DOWNLOADING || !it.seen }

    fun markDownloadsSeen() {
        _downloads.value = _downloads.value.map {
            if (it.state != DownloadState.DOWNLOADING) it.copy(seen = true) else it
        }
    }

    fun clearFinishedDownloads() {
        _downloads.value = _downloads.value.filter { it.state == DownloadState.DOWNLOADING }
    }


    private fun sessionWorkspaceDir(): File? {
        val sid = sessionId ?: return null
        return File(File(File(context.filesDir, "minis-sessions"), sid), "workspace")
            .apply { mkdirs() }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = sanitizeDownloadName(name)
        var f = File(dir, safe)
        if (!f.exists()) return f
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base-$i" else "$base-$i.$ext")
            i++
        }
        return f
    }

    internal fun startUrlDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val dir = sessionWorkspaceDir() ?: run {
            Log.w(TAG, "download rejected: no session bound to pool")
            return
        }
        val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val dest = uniqueFile(dir, name)
        onDownloadEvent?.invoke("Downloading ${middleTruncated(dest.name)}…")
        val id = registerDownload(dest, contentLength)
        val job = downloadScope.launch {
            try {
                val headers = buildMap {
                    userAgent?.takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
                    android.webkit.CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
                }
                val conn = com.openminis.app.network.NetworkUrlRequest.open(
                    com.openminis.app.network.NetworkScope.BROWSER.tag, url,
                    headers = headers, connectTimeoutMs = 15_000, readTimeoutMs = 30_000,
                )
                val total = if (contentLength > 0) contentLength else conn.contentLength
                conn.body().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            updateDownloadProgress(id, dest.name, copied, total)
                        }
                    }
                }
                conn.close()
                finishDownload(id, dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "download cancelled: ${dest.name}")
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = "Cancelled")
            } catch (t: Throwable) {
                Log.w(TAG, "download failed: ${dest.name} — ${t.message}")
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = t.message ?: "error")
                onDownloadEvent?.invoke("Download failed: ${middleTruncated(dest.name)} — ${t.message}")
            }
        }
        downloadJobs[id] = job
    }

    internal fun saveBlobDownload(data: ByteArray, filename: String, mimeType: String?) {
        val dir = sessionWorkspaceDir() ?: run {
            Log.w(TAG, "blob download rejected: no session bound to pool")
            return
        }
        var name = filename
        if ((name.endsWith(".bin") || !name.contains('.')) && mimeType != null) {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { ext ->
                name = name.substringBeforeLast('.') + "." + ext
            }
        }
        val dest = uniqueFile(dir, name)
        val id = registerDownload(dest, data.size.toLong())
        val job = downloadScope.launch {
            try {
                dest.writeBytes(data)
                finishDownload(id, dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = "Cancelled")
            } catch (t: Throwable) {
                Log.w(TAG, "blob download save failed: ${t.message}")
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = t.message ?: "error")
                onDownloadEvent?.invoke("Download failed: ${middleTruncated(dest.name)} — ${t.message}")
            }
        }
        downloadJobs[id] = job
    }

    private fun finishDownload(id: Long, dest: File) {
        val size = dest.length()
        settleDownload(id, DownloadState.COMPLETED, bytes = size)
        val sizeText = android.text.format.Formatter.formatShortFileSize(context, size)
        Log.i(TAG, "download finished: ${dest.name} ($sizeText) → ${dest.absolutePath}")
        onDownloadEvent?.invoke("Downloaded ${middleTruncated(dest.name)} ($sizeText)")
    }

    private fun wireDownloadHandlers(manager: BrowserUseManager) {
        manager.onDownloadStart = { url, ua, cd, mime, len -> startUrlDownload(url, ua, cd, mime, len) }
        manager.onBlobDownloadData = { data, name, mime -> saveBlobDownload(data, name, mime) }
    }


    suspend fun execute(
        input: BrowserActionInput,
        singleTab: Boolean = false,
        owner: String? = null,
    ): BrowserActionResult {
        fun ownershipViolation(tabId: Int?): BrowserActionResult? {
            if (tabId == null || isPrivileged(owner)) return null
            if (!_tabs.value.any { it.id == tabId }) return null
            if (mayUse(tabId, owner)) return null
            return notYourTabError(tabId, owner!!)
        }
        return when (input.action) {
            BrowserAction.NEW_TAB -> newTab(input.url, owner)
            BrowserAction.CLOSE_TAB -> {
                val target = resolveCloseTarget(input.tabId, owner)
                ownershipViolation(target) ?: closeTab(target)
            }
            BrowserAction.LIST_TABS -> listTabs(owner)
            BrowserAction.SET_VIEWPORT -> handleSetViewport(input)
            else -> {
                ownershipViolation(input.tabId)?.let { return it }
                val serialTabId = input.tabId?.takeIf { reqId ->
                    _tabs.value.any { it.id == reqId }
                }
                if (serialTabId != null) {
                    executeSerialized(serialTabId, input)
                } else {
                    val mustFollowSelected = singleTab || !input.action.opensNewPage
                    val agentTarget = if (isPrivileged(owner)) null else {
                        preferredTab(owner)
                            ?: _tabs.value.firstOrNull { !it.inUse && tabOwner[it.id] == owner }?.id
                    }
                    if (agentTarget != null) {
                        withContext(Dispatchers.Main) { lastTabByOwner[owner!!] = agentTarget }
                        executeSerialized(agentTarget, input)
                    } else if (!isPrivileged(owner)) {
                        runAcquiredAction(input, implicitTab = true, owner = owner)
                    } else if (mustFollowSelected && _tabs.value.isNotEmpty()) {
                        val sel = _selectedTabId.value
                        val targetId = if (_tabs.value.any { it.id == sel }) sel
                            else _tabs.value.maxByOrNull { it.lastActivityDate }!!.id
                        executeSerialized(targetId, input)
                    } else {
                        runAcquiredAction(input, implicitTab = true, owner = owner)
                    }
                }
            }
        }
    }

    private suspend fun executeSerialized(tabId: Int, input: BrowserActionInput): BrowserActionResult {
        val mutex = lockForTab(tabId)
        if (!BrowserActionGuard.lockWithin(mutex, TAB_SERIAL_WAIT_TIMEOUT_MS)) {
            return BrowserActionResult.error(
                context.getString(
                    R.string.browser_use_tab_busy_timeout,
                    tabId,
                    (TAB_SERIAL_WAIT_TIMEOUT_MS / 1000L).toInt(),
                ),
            )
        }
        try {
            return runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runAcquiredAction(
        input: BrowserActionInput,
        implicitTab: Boolean,
        acquireTabId: Int? = null,
        owner: String? = null,
    ): BrowserActionResult {
        val requestedId = acquireTabId ?: input.tabId
        if (requestedId != null) {
            consumePreemptNotice(requestedId)?.let { return BrowserActionResult.error(it) }
        }
        val tab = acquireTab(requestedId, owner)
            ?: return BrowserActionResult.error("Failed to acquire browser tab")
        val rebuildNote = rebuildNotices.remove(tab.id)
        return try {
            val outcome = BrowserActionGuard.runGuarded(
                onSuspect = { reason -> tab.manager.markWedged(reason) },
            ) { tab.manager.execute(input) }
            val result = when (outcome) {
                is BrowserActionGuard.Outcome.Done -> outcome.value
                is BrowserActionGuard.Outcome.DeadlineExceeded -> return BrowserActionResult.error(
                    withRebuildNote(
                        rebuildNote,
                        "Browser action '${input.action.value}' timed out on tab ${tab.id} after " +
                            "${BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS / 1000}s — the page stopped " +
                            "responding. The tab has been reset and will be rebuilt on your next " +
                            "browser call; retry the action.",
                    ),
                )
                is BrowserActionGuard.Outcome.Wedged -> return BrowserActionResult.error(
                    withRebuildNote(
                        rebuildNote,
                        "Browser tab ${tab.id} is not usable: ${outcome.message}. It will be " +
                            "rebuilt on your next browser call; retry the action.",
                    ),
                )
            }
            consumePreemptNotice(tab.id)?.let { return BrowserActionResult.error(it) }
            val withDialogs = tab.manager.drainInterceptedDialogReport()
                ?.let { result.copy(text = it + result.text) }
                ?: result
            val withRebuild = if (rebuildNote != null) {
                withDialogs.copy(text = withRebuildNote(rebuildNote, withDialogs.text))
            } else {
                withDialogs
            }
            stampTabId(withRebuild.copy(pageURL = tab.manager.currentURL.value), tab.id)
        } finally {
            tab.lastActivityDate = Date()
            if (implicitTab) {
                armImplicitGraceRelease(tab)
            } else {
                tab.inUseGraceJob?.cancel()
                tab.inUseGraceJob = null
                tab.inUse = false
            }
            updateTabs()
            saveState()
        }
    }

    private fun withRebuildNote(note: String?, text: String): String =
        if (note == null) text else "$note\n$text"

    private fun stampTabId(result: BrowserActionResult, targetId: Int): BrowserActionResult {
        val needsLine = !result.text.contains("tab_id:")
        val text = if (needsLine) {
            val needsNewline = result.text.isNotEmpty() && !result.text.endsWith("\n")
            result.text + (if (needsNewline) "\n" else "") + "  tab_id: $targetId"
        } else {
            result.text
        }
        return result.copy(text = text, tabId = targetId)
    }

    private fun armImplicitGraceRelease(tab: Tab) {
        tab.inUse = true
        tab.inUseGraceJob?.cancel()
        tab.inUseGraceJob = evictionScope.launch {
            delay(IMPLICIT_TAB_GRACE_MS)
            withContext(Dispatchers.Main) {
                tab.inUse = false
                tab.inUseGraceJob = null
                tab.lastActivityDate = Date()
                updateTabs()
            }
        }
    }

    private suspend fun acquireTab(
        requestedTabId: Int? = null,
        owner: String? = null,
    ): Tab? = withContext(Dispatchers.Main) {
        val requested = dropAndRebuildUnusableTabs(requestedTabId) ?: requestedTabId
        var currentTabs = _tabs.value.toMutableList()

        val mayTakeRequested = requested != null &&
            currentTabs.any { it.id == requested } &&
            mayUse(requested, owner)
        val tab = if (mayTakeRequested) {
            currentTabs.first { it.id == requested }
        } else {
            var picked = currentTabs.firstOrNull { !it.inUse && mayUse(it.id, owner) }
                ?: createTab(currentTabs, owner = owner)
            if (picked == null) {
                val waitDeadline = IMPLICIT_TAB_WAIT_MS
                var waited = 0L
                while (waited < waitDeadline) {
                    delay(IMPLICIT_TAB_WAIT_POLL_MS)
                    waited += IMPLICIT_TAB_WAIT_POLL_MS
                    currentTabs = _tabs.value.toMutableList()
                    picked = currentTabs.firstOrNull { !it.inUse && mayUse(it.id, owner) }
                        ?: createTab(currentTabs, owner = owner)
                    if (picked != null) break
                }
            }
            picked ?: currentTabs.firstOrNull()
        }

        if (tab != null) {
            tab.inUseGraceJob?.cancel()
            tab.inUseGraceJob = null
            tab.inUse = true
            tab.lastActivityDate = Date()
            if (isPrivileged(owner)) _selectedTabId.value = tab.id
            _tabs.value = currentTabs
            if (owner != null && !isPrivileged(owner)) {
                tabOwner.putIfAbsent(tab.id, owner)
                if (tabOwner[tab.id] == owner) lastTabByOwner[owner] = tab.id
            }
        }

        if (tab != null && tab.needsInitialBlankPage) {
            tab.needsInitialBlankPage = false
            tab.manager.loadBlankPage()
        }
        tab
    }

    private fun dropAndRebuildUnusableTabs(requestedTabId: Int?): Int? {
        val bad = _tabs.value.filter { it.manager.isUnusable }
        if (bad.isEmpty()) return null
        Log.w(
            TAG,
            "acquireTab: dropping ${bad.size} unusable tab(s) " +
                bad.joinToString(prefix = "(", postfix = ")") {
                    "${it.id}:" + if (it.manager.isRendererDead) "renderer-gone" else "unresponsive"
                },
        )
        val badIds = bad.map { it.id }.toSet()
        val survivors = _tabs.value.filterNot { it.id in badIds }.toMutableList()
        _tabs.value = survivors.toList()
        var replacementForRequested: Int? = null
        for (dead in bad) {
            val url = dead.manager.currentURL.value.takeIf { it.isNotEmpty() && it != "about:blank" }
            val owner = tabOwner[dead.id]
            val wasSelected = _selectedTabId.value == dead.id
            val pointingOwners = lastTabByOwner.filterValues { it == dead.id }.keys.toList()
            val cause = if (dead.manager.isRendererDead) "crashed (its renderer process died)"
                else "stopped responding"
            releaseTabResources(dead)
            tabOwner.remove(dead.id)
            lastTabByOwner.entries.removeAll { it.value == dead.id }
            savedURLs.remove(dead.id)

            if (dead.id != requestedTabId && !wasSelected) continue
            val fresh = createTab(survivors, url = url, owner = owner)
            if (fresh == null) {
                Log.w(TAG, "acquireTab: could not rebuild tab ${dead.id} (no slot)")
                continue
            }
            if (owner != null) tabOwner[fresh.id] = owner
            pointingOwners.forEach { lastTabByOwner[it] = fresh.id }
            if (wasSelected) _selectedTabId.value = fresh.id
            if (dead.id == requestedTabId) replacementForRequested = fresh.id
            rebuildNotices[fresh.id] =
                "[Tab rebuilt] Browser tab ${dead.id} $cause and was rebuilt as tab ${fresh.id}" +
                    (url?.let { "; page reloaded from $it" } ?: "; it had no page to reload") +
                    ". Earlier element references and page state are gone — re-locate " +
                    "elements before interacting, and use tab_id ${fresh.id} from now on."
            Log.i(TAG, "acquireTab: rebuilt tab ${dead.id} as ${fresh.id} (url=${url ?: "none"})")
        }
        if (survivors.isNotEmpty() && survivors.none { it.id == _selectedTabId.value }) {
            _selectedTabId.value = survivors.first().id
        }
        saveState()
        return replacementForRequested
    }

    private fun createTab(
        tabs: MutableList<Tab>,
        url: String? = null,
        owner: String? = null,
    ): Tab? {
        if (tabs.size >= effectiveMaxTabs(owner)) return null

        if (!BrowserTabPoolRegistry.requestSlot(this)) {
            Log.i(TAG, "Registry denied a global tab slot (cap ${BrowserTabPoolRegistry.GLOBAL_TAB_CAP})")
            return null
        }

        syncGlobalDefaultsFromStore()
        val id = nextTabId++
        val webView = WebView(context)
        val manager = BrowserUseManager(
            webView,
            userAgentProfile,
            sessionIdProvider = { sessionId },
            appContext = context.applicationContext,
        )
        if (userAgentProfile == UserAgentProfile.CUSTOM && !customUserAgentString.isNullOrEmpty()) {
            manager.setUserAgent(userAgentProfile, customUserAgentString)
        }
        val (vpW, vpH) = resolvedViewportSize()
        manager.applyViewport(vpW, vpH)
        manager.viewportOverride = { customViewportOverride() }

        manager.onNewWindow = { resultMsg -> handleNewWindow(resultMsg) }
        manager.onCloseWindow = { handleCloseWindow(manager) }
        wireDownloadHandlers(manager)

        val tab = Tab(id = id, manager = manager)
        tabs.add(tab)
        _tabs.value = tabs.toList()

        val loadUrl = url ?: savedURLs.remove(id)
        if (loadUrl != null) {
            manager.loadURL(loadUrl)
        } else {
            tab.needsInitialBlankPage = true
        }

        Log.i(TAG, "Created tab $id (total: ${tabs.size})")
        return tab
    }


    private suspend fun newTab(url: String?, owner: String? = null): BrowserActionResult = withContext(Dispatchers.Main) {
        val currentTabs = _tabs.value.toMutableList()
        if (owner != null && !isPrivileged(owner)) {
            val mine = tabIdsOwnedBy(owner)
            if (mine.size >= agentTabQuota) {
                return@withContext BrowserActionResult.error(
                    "You already have ${mine.size} tab(s) (limit $agentTabQuota per agent). " +
                        "Reuse tab id(s) ${mine.joinToString(", ")} — navigate them to the next page — " +
                        "or close_tab one of yours first.",
                )
            }
        }
        val ceiling = effectiveMaxTabs(owner)
        if (currentTabs.size >= ceiling) {
            return@withContext BrowserActionResult.error("Maximum $ceiling tabs reached")
        }
        val tab = createTab(currentTabs, url, owner)
        if (tab == null) {
            return@withContext BrowserActionResult.error(
                if (currentTabs.size < ceiling) {
                    "Cannot open another browser tab: the device-wide limit of " +
                        "${BrowserTabPoolRegistry.GLOBAL_TAB_CAP} is reached and every other " +
                        "tab is busy. Reuse one of your existing tabs (list_tabs) or " +
                        "close_tab one first."
                } else {
                    "Failed to create new tab"
                },
            )
        }
        if (owner != null && !isPrivileged(owner)) {
            tabOwner[tab.id] = owner
            lastTabByOwner[owner] = tab.id
        }
        if (isPrivileged(owner)) _selectedTabId.value = tab.id
        if (tab.needsInitialBlankPage) {
            tab.needsInitialBlankPage = false
            tab.manager.loadBlankPage()
        }
        saveState()
        BrowserActionResult(
            text = "Opened new tab ${tab.id}" + (if (url != null) " at $url" else "") +
                ". Use tab_id: ${tab.id} to target this tab.",
            tabId = tab.id,
        )
    }

    internal fun resolveCloseTarget(tabId: Int?, owner: String?): Int? {
        if (isPrivileged(owner)) return tabId
        if (tabId != null) return tabId
        return preferredTab(owner) ?: tabIdsOwnedBy(owner!!).firstOrNull() ?: -1
    }

    private suspend fun closeTab(tabId: Int?): BrowserActionResult = withContext(Dispatchers.Main) {
        val id = tabId ?: _selectedTabId.value
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == id }
        if (idx < 0) return@withContext BrowserActionResult.error("Tab $id not found")

        val closing = currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        releaseTabResources(closing)
        tabOwner.remove(id)
        lastTabByOwner.entries.removeAll { it.value == id }

        if (currentTabs.isNotEmpty() && _selectedTabId.value == id) {
            _selectedTabId.value = currentTabs.first().id
        }
        saveState()
        BrowserActionResult(text = "Closed tab $id")
    }

    private fun listTabs(owner: String? = null): BrowserActionResult {
        val visible = if (isPrivileged(owner)) _tabs.value
        else _tabs.value.filter { tabOwner[it.id] == owner }
        val lines = visible.map { tab ->
            val marker = if (tab.id == _selectedTabId.value) "*" else " "
            val title = tab.manager.pageTitle.value.ifEmpty { "(blank)" }
            val url = tab.manager.currentURL.value.ifEmpty { "about:blank" }
            "$marker Tab ${tab.id}: $title — $url"
        }
        return if (lines.isEmpty()) {
            BrowserActionResult(
                text = if (isPrivileged(owner)) "No open tabs"
                else "You have no tabs open. Use action: new_tab to open one.",
            )
        } else {
            BrowserActionResult(text = lines.joinToString("\n"))
        }
    }


    private fun handleNewWindow(resultMsg: Message) {
        val currentTabs = _tabs.value.toMutableList()
        if (currentTabs.size >= MAX_TABS) {
            Log.w(TAG, "window.open rejected: max tabs reached")
            return
        }
        syncGlobalDefaultsFromStore()
        val id = nextTabId++
        val newWebView = WebView(context)
        val manager = BrowserUseManager(
            newWebView,
            userAgentProfile,
            sessionIdProvider = { sessionId },
            appContext = context.applicationContext,
        )
        if (userAgentProfile == UserAgentProfile.CUSTOM && !customUserAgentString.isNullOrEmpty()) {
            manager.setUserAgent(userAgentProfile, customUserAgentString)
        }
        val (vpW, vpH) = resolvedViewportSize()
        manager.applyViewport(vpW, vpH)
        manager.viewportOverride = { customViewportOverride() }
        manager.onNewWindow = { msg -> handleNewWindow(msg) }
        manager.onCloseWindow = { handleCloseWindow(manager) }
        wireDownloadHandlers(manager)

        val tab = Tab(id = id, manager = manager)
        currentTabs.add(tab)
        _tabs.value = currentTabs
        _selectedTabId.value = id

        val transport = resultMsg.obj as? WebView.WebViewTransport
        transport?.webView = newWebView
        resultMsg.sendToTarget()

        Log.i(TAG, "window.open → created tab $id")
    }

    private fun handleCloseWindow(manager: BrowserUseManager) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.manager === manager }
        if (idx >= 0) {
            val closing = currentTabs.removeAt(idx)
            val closedId = closing.id
            _tabs.value = currentTabs
            if (_selectedTabId.value == closedId && currentTabs.isNotEmpty()) {
                _selectedTabId.value = currentTabs.first().id
            }
            closing.manager.webView.post { releaseTabResources(closing) }
            Log.i(TAG, "window.close → removed tab $closedId")
        }
    }


    fun selectTab(id: Int) {
        if (_tabs.value.any { it.id == id }) {
            _selectedTabId.value = id
        }
    }

    suspend fun newTabFromUI(): Tab? = withContext(Dispatchers.Main) {
        val currentTabs = _tabs.value.toMutableList()
        if (currentTabs.size >= MAX_TABS) return@withContext null
        val tab = createTab(currentTabs) ?: return@withContext null
        _selectedTabId.value = tab.id
        if (tab.needsInitialBlankPage) {
            tab.needsInitialBlankPage = false
            tab.manager.loadBlankPage()
        }
        saveState()
        tab
    }

    suspend fun closeTabFromUI(tabId: Int) = withContext(Dispatchers.Main) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@withContext
        val closing = currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        releaseTabResources(closing)
        if (_selectedTabId.value == tabId && currentTabs.isNotEmpty()) {
            _selectedTabId.value = currentTabs.first().id
        }
        saveState()
    }

    fun selectOrCreateTabForURL(url: String): Tab? {
        if (url.isBlank()) return _tabs.value.firstOrNull()
        val target = normalizeUrlForMatch(url)
        val currentTabs = _tabs.value.toMutableList()
        val match = currentTabs.firstOrNull { normalizeUrlForMatch(it.manager.currentURL.value) == target }
        if (match != null) {
            _selectedTabId.value = match.id
            match.lastActivityDate = Date()
            updateTabs()
            return match
        }
        val tab = createTab(currentTabs, url) ?: return null
        _selectedTabId.value = tab.id
        saveState()
        return tab
    }

    private fun normalizeUrlForMatch(raw: String): String {
        if (raw.isEmpty()) return ""
        return try {
            val uri = android.net.Uri.parse(raw)
            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.lowercase() ?: ""
            val path = (uri.path ?: "").trimEnd('/')
            "$scheme://$host$path"
        } catch (_: Exception) {
            raw.trimEnd('/')
        }
    }

    fun ensureTabForUI(): Tab {
        val currentTabs = _tabs.value.toMutableList()
        val existing = currentTabs.firstOrNull()
        if (existing != null) {
            if (_selectedTabId.value != existing.id &&
                currentTabs.none { it.id == _selectedTabId.value }) {
                _selectedTabId.value = existing.id
            }
            return existing
        }
        val tab = createTab(currentTabs)!!
        _selectedTabId.value = tab.id
        saveState()
        return tab
    }


    private fun syncGlobalDefaultsFromStore() {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val profileName = prefs.getString("user_agent_profile", null)
        val profile = profileName?.let { runCatching { UserAgentProfile.valueOf(it) }.getOrNull() }
        if (profile != null) _userAgentProfile.value = profile
        customUserAgentString = prefs.getString("custom_user_agent", null)?.ifEmpty { null }
        _customViewportWidth.value = prefs.getInt(PREF_GLOBAL_VIEWPORT_WIDTH, 0).coerceAtLeast(0)
        _customViewportHeight.value = prefs.getInt(PREF_GLOBAL_VIEWPORT_HEIGHT, 0).coerceAtLeast(0)
    }

    fun setUserAgentFromUI(profile: UserAgentProfile, customUA: String? = null) {
        userAgentProfile = profile
        customUserAgentString = customUA
    }


    fun releaseAllTabsToUser() {
        _tabs.value = _tabs.value.map { it.copy(inUse = false) }
        saveState()
    }

    fun destroyAllTabs() {
        val doomed = _tabs.value
        if (doomed.isEmpty()) return
        _tabs.value = emptyList()
        for (tab in doomed) {
            releaseTabResources(tab)
            tabOwner.remove(tab.id)
            lastTabByOwner.entries.removeAll { it.value == tab.id }
        }
        savedURLs.clear()
        saveState()
        Log.i(TAG, "destroyed ${doomed.size} tab(s) and released their WebViews")
    }


    fun evictIdleTabs() {
        val now = System.currentTimeMillis()
        val currentTabs = _tabs.value.toMutableList()
        val timeoutMs = idleTimeoutMs
        val toRemove = currentTabs.filter { !it.inUse && (now - it.lastActivityDate.time) >= timeoutMs }
        for (tab in toRemove) {
            val url = tab.manager.currentURL.value
            if (url.isNotEmpty()) savedURLs[tab.id] = url
            currentTabs.remove(tab)
            releaseTabResources(tab)
            Log.i(TAG, "Evicted idle tab ${tab.id}")
        }
        if (toRemove.isNotEmpty()) {
            _tabs.value = currentTabs
            if (currentTabs.isNotEmpty() && currentTabs.none { it.id == _selectedTabId.value }) {
                _selectedTabId.value = currentTabs.first().id
            }
            saveState()
        }
    }


    private val preemptedTabIds = mutableSetOf<Int>()

    private fun consumePreemptNotice(id: Int): String? {
        if (!preemptedTabIds.remove(id)) return null
        return "Browser tab $id was reclaimed under memory pressure (another " +
            "session needed the slot). Please retry — the page URL was saved " +
            "and will be restored."
    }

    private fun releaseTabResources(tab: Tab) {
        tab.inUseGraceJob?.cancel()
        tab.inUseGraceJob = null
        tabLocks.remove(tab.id)
        try {
            val webView = tab.manager.webView
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "Tab ${tab.id} teardown failed: ${t.message}")
        }
    }

    private fun forgetTab(id: Int, remaining: List<Tab>) {
        tabOwner.remove(id)
        lastTabByOwner.entries.removeAll { it.value == id }
        if (remaining.isNotEmpty() && remaining.none { it.id == _selectedTabId.value }) {
            _selectedTabId.value = remaining.first().id
        }
    }

    override fun registrySnapshot(): List<BrowserTabPoolRegistry.TabInfo> =
        _tabs.value.map { BrowserTabPoolRegistry.TabInfo(it.id, it.inUse, it.lastActivityDate) }

    override fun evictTabForRegistry(id: Int) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = currentTabs[idx]
        if (tab.inUse) return
        val url = tab.manager.currentURL.value
        if (url.isNotEmpty()) savedURLs[id] = url
        currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        releaseTabResources(tab)
        forgetTab(id, currentTabs)
        saveState()
        Log.i(TAG, "Evicted tab $id at registry request (url=${url.take(60)})")
    }

    override fun preemptTabForRegistry(id: Int) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = currentTabs[idx]
        val url = tab.manager.currentURL.value
        if (url.isNotEmpty()) savedURLs[id] = url
        currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        releaseTabResources(tab)
        preemptedTabIds.add(id)
        forgetTab(id, currentTabs)
        saveState()
        Log.i(TAG, "Preempted in-use tab $id at registry request (url=${url.take(60)})")
    }

    override fun evictAllIdleForMemoryPressure(): Int {
        val currentTabs = _tabs.value.toMutableList()
        val toRemove = currentTabs.filter { !it.inUse }
        if (toRemove.isEmpty()) return 0
        for (tab in toRemove) {
            val url = tab.manager.currentURL.value
            if (url.isNotEmpty()) savedURLs[tab.id] = url
            currentTabs.remove(tab)
            releaseTabResources(tab)
            tabOwner.remove(tab.id)
            lastTabByOwner.entries.removeAll { it.value == tab.id }
        }
        _tabs.value = currentTabs
        if (currentTabs.isNotEmpty() && currentTabs.none { it.id == _selectedTabId.value }) {
            _selectedTabId.value = currentTabs.first().id
        }
        saveState()
        Log.i(TAG, "Evicted ${toRemove.size} idle tab(s) under memory pressure")
        return toRemove.size
    }


    fun resolvedViewportSize(): Pair<Int, Int> {
        if (_sessionViewportWidth.value > 0 && _sessionViewportHeight.value > 0) {
            return _sessionViewportWidth.value to _sessionViewportHeight.value
        }
        if (_customViewportWidth.value > 0 && _customViewportHeight.value > 0) {
            return _customViewportWidth.value to _customViewportHeight.value
        }
        return userAgentProfile.viewportSize
    }

    private fun customViewportOverride(): Pair<Int, Int>? {
        if (_sessionViewportWidth.value > 0 && _sessionViewportHeight.value > 0) {
            return _sessionViewportWidth.value to _sessionViewportHeight.value
        }
        if (_customViewportWidth.value > 0 && _customViewportHeight.value > 0) {
            return _customViewportWidth.value to _customViewportHeight.value
        }
        return null
    }

    fun hasCustomViewport(): Boolean =
        (_sessionViewportWidth.value > 0 && _sessionViewportHeight.value > 0) ||
            (_customViewportWidth.value > 0 && _customViewportHeight.value > 0)

    suspend fun setGlobalViewport(width: Int, height: Int) {
        _customViewportWidth.value = width.coerceAtLeast(0)
        _customViewportHeight.value = height.coerceAtLeast(0)
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(PREF_GLOBAL_VIEWPORT_WIDTH, _customViewportWidth.value)
            .putInt(PREF_GLOBAL_VIEWPORT_HEIGHT, _customViewportHeight.value)
            .apply()
    }

    suspend fun setSessionViewport(width: Int, height: Int) {
        _sessionViewportWidth.value = width.coerceAtLeast(0)
        _sessionViewportHeight.value = height.coerceAtLeast(0)
        applyViewportToAllTabs()
        saveState()
    }

    suspend fun resetSessionViewport() {
        _sessionViewportWidth.value = 0
        _sessionViewportHeight.value = 0
        applyViewportToAllTabs()
        saveState()
    }

    private suspend fun applyViewportToAllTabs() {
        val override = customViewportOverride()
        withContext(Dispatchers.Main) {
            for (tab in _tabs.value) {
                val (w, h) = override ?: tab.manager.profileFlow.value.viewportSize
                tab.manager.applyViewport(w, h)
                tab.manager.reloadAndWait()
            }
        }
    }

    private suspend fun handleSetViewport(input: BrowserActionInput): BrowserActionResult = withContext(Dispatchers.Main) {
        if (input.reset) {
            resetSessionViewport()
            val (w, h) = resolvedViewportSize()
            return@withContext BrowserActionResult(text = "Viewport reset to default (${w}x$h)")
        }
        val w = input.viewportWidth
        val h = input.viewportHeight
        if (w == null || h == null || w <= 0 || h <= 0) {
            return@withContext BrowserActionResult.error(
                "set_viewport requires positive --width and --height, or --reset to restore defaults"
            )
        }
        setSessionViewport(w, h)
        BrowserActionResult(text = "Viewport set to ${w}x$h (session override)")
    }


    private fun saveState() {
        val sid = sessionId ?: return
        try {
            val dir = File(context.filesDir, "browser_tabs")
            dir.mkdirs()
            val file = File(dir, "$sid.json")
            val json = JSONObject()
            val urlsJson = JSONObject()
            for (tab in _tabs.value) {
                val url = tab.manager.currentURL.value
                if (url.isNotEmpty()) urlsJson.put(tab.id.toString(), url)
            }
            for ((id, url) in savedURLs) {
                urlsJson.put(id.toString(), url)
            }
            json.put("tabURLs", urlsJson)
            json.put("selectedTabId", _selectedTabId.value)
            if (_sessionViewportWidth.value > 0 && _sessionViewportHeight.value > 0) {
                json.put("sessionViewportWidth", _sessionViewportWidth.value)
                json.put("sessionViewportHeight", _sessionViewportHeight.value)
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tab state: ${e.message}")
        }
    }

    private fun loadSavedState() {
        val sid = sessionId ?: return
        try {
            val file = File(context.filesDir, "browser_tabs/$sid.json")
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val urlsJson = json.optJSONObject("tabURLs")
            if (urlsJson != null) {
                val keys = urlsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    savedURLs[key.toInt()] = urlsJson.getString(key)
                }
                _selectedTabId.value = json.optInt("selectedTabId", 0)
            }
            val w = json.optInt("sessionViewportWidth", 0)
            val h = json.optInt("sessionViewportHeight", 0)
            if (w > 0 && h > 0) {
                _sessionViewportWidth.value = w
                _sessionViewportHeight.value = h
                if (_tabs.value.isNotEmpty()) {
                    evictionScope.launch { applyViewportToAllTabs() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tab state: ${e.message}")
        }
    }

    private fun updateTabs() {
        _tabs.value = _tabs.value.toList()
    }
}
