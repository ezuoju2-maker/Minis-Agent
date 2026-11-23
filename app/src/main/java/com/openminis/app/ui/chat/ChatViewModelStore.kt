package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

object ChatViewModelStore {

    private const val TAG = "ChatVMStore"

    private val stores = LinkedHashMap<String, ViewModelStore>()


    enum class PoolKind { NORMAL, CHILD }

    private val poolKinds = mutableMapOf<String, PoolKind>()

    internal const val MAX_CACHED_NORMAL = 8

    internal const val MAX_CACHED_CHILD = 6

    private val aliases = mutableMapOf<String, String>()

    private val aliasGeneration = androidx.compose.runtime.mutableIntStateOf(0)

    private fun resolveKey(sessionId: String): String =
        aliases[sessionId] ?: sessionId

    @Synchronized
    fun resolvePersistedId(sessionId: String): String = resolveKey(sessionId)

    @Synchronized
    fun existing(sessionId: String): ChatViewModel? {
        val store = stores[resolveKey(sessionId)] ?: return null
        return viewModelIn(store)
    }

    private fun viewModelIn(store: ViewModelStore): ChatViewModel? = runCatching {
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    throw IllegalStateException("no existing view model")
            },
        )[ChatViewModel::class.java]
    }.getOrNull()

    @Synchronized
    fun isLiveStore(sessionId: String, store: ViewModelStore): Boolean =
        stores[resolveKey(sessionId)] === store

    private fun busyKeys(): Set<String> {
        val tracked = runCatching {
            com.openminis.app.service.SessionActivityTracker.activeSessions.value
                .map { resolveKey(it) }
        }.getOrDefault(emptyList())
        val inFlight = stores.entries
            .filter { (_, store) -> runCatching { viewModelIn(store)?.hasWorkInFlight() == true }.getOrDefault(false) }
            .map { it.key }
        return (tracked + inFlight + pendingSends.keys + viewers.keys).toSet()
    }

    private val viewers = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun holdWhileViewed(sessionId: String): () -> Unit {
        val key = resolveKey(sessionId)
        viewers.merge(key, 1, Int::plus)
        var released = false
        return {
            if (!released) {
                released = true
                viewers.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
            }
        }
    }

    private val pendingSends = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun <T> holdingForSend(sessionId: String, block: suspend () -> T): T {
        val key = resolveKey(sessionId)
        pendingSends.merge(key, 1, Int::plus)
        try {
            return block()
        } finally {
            pendingSends.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
        }
    }

    @androidx.compose.runtime.Composable
    fun rememberPersistedId(sessionId: String): String {
        val generation = aliasGeneration.intValue
        return androidx.compose.runtime.remember(sessionId, generation) {
            resolveKey(sessionId)
        }
    }

    @Synchronized
    @JvmOverloads
    fun ownerFor(sessionId: String, kind: PoolKind = PoolKind.NORMAL): ViewModelStoreOwner {
        val key = resolveKey(sessionId)
        val existing = stores.remove(key)
        val store = if (existing != null) {
            existing
        } else {
            poolKinds.putIfAbsent(key, kind)
            Log.d(
                TAG,
                "allocate store for $key kind=${poolKinds[key]} " +
                    "(total=${stores.size + 1}, pool=${poolSize(poolKinds[key]) + 1})",
            )
            ViewModelStore()
        }
        stores[key] = store
        trimToCapacity(poolKinds[key] ?: PoolKind.NORMAL)
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    private fun poolSize(kind: PoolKind?): Int {
        if (kind == null) return 0
        return stores.keys.count { (poolKinds[it] ?: PoolKind.NORMAL) == kind }
    }

    private fun trimToCapacity(kind: PoolKind) {
        val cap = when (kind) {
            PoolKind.NORMAL -> MAX_CACHED_NORMAL
            PoolKind.CHILD -> MAX_CACHED_CHILD
        }
        val inPool = stores.keys.filter { (poolKinds[it] ?: PoolKind.NORMAL) == kind }
        if (inPool.size <= cap) return
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        val justInserted = inPool.lastOrNull()
        val evictable = inPool.filter { it != onScreen && it != justInserted && it !in busy }
        var overflow = inPool.size - cap
        for (key in evictable) {
            if (overflow <= 0) break
            dropStoreKeepingChildTag(key)
            overflow--
            Log.d(
                TAG,
                "evict store for $key kind=$kind (lru, remaining=${stores.size}, " +
                    "pool=${poolSize(kind)})",
            )
        }
    }

    @Synchronized
    fun handleMemoryPressure(): Int {
        if (stores.isEmpty()) return 0
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        val evictable = stores.keys.filter { it != onScreen && it !in busy }
        for (key in evictable) {
            dropStoreKeepingChildTag(key)
        }
        if (evictable.isNotEmpty()) {
            Log.d(
                TAG,
                "memory pressure: released ${evictable.size} store(s), " +
                    "remaining=${stores.size} (kept onScreen=${onScreen != null} busy=${busy.size})",
            )
        }
        return evictable.size
    }

    private fun dropStoreKeepingChildTag(key: String) {
        stores.remove(key)?.clear()
        if (poolKinds[key] != PoolKind.CHILD) poolKinds.remove(key)
        forgetDraft(key)
    }

    @Synchronized
    fun release(sessionId: String) {
        val key = resolveKey(sessionId)
        aliases.entries.removeAll { it.value == key }
        aliasGeneration.intValue++
        poolKinds.remove(key)
        forgetDraft(key)
        stores.remove(key)?.let {
            it.clear()
            Log.d(TAG, "release store for $key (remaining=${stores.size})")
        }
    }

    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        get() = activeSessionIdInternal?.let { resolveKey(it) }

    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdInternal = sessionId
    }

    @Synchronized
    fun clearActiveSession(sessionId: String) {
        val current = activeSessionIdInternal ?: return
        if (resolveKey(current) != resolveKey(sessionId)) return
        activeSessionIdInternal = null
    }

    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val store = stores.remove(fromSessionId)
        if (store != null) {
            stores[toSessionId] = store
        }
        poolKinds.remove(fromSessionId)?.let { poolKinds[toSessionId] = it }
        forgetDraft(fromSessionId)
        aliases[fromSessionId] = toSessionId
        aliasGeneration.intValue++
        Log.d(TAG, "rename store $fromSessionId -> $toSessionId (alias kept)")
    }


    internal const val PARKED_DRAFT_LIFETIME_MS = 15 * 60 * 1000L

    @Volatile
    internal var parkedDraftClock: () -> Long = { System.currentTimeMillis() }

    private data class ParkedDraft(val draftId: String, val parkedAtMs: Long)

    private val parkedDrafts = mutableMapOf<String, ParkedDraft>()

    private val newChatDraftIds = mutableSetOf<String>()

    private fun draftSlotKey(groupId: String?, folderId: String?): String =
        "grp=${groupId.orEmpty()}|fld=${folderId.orEmpty()}"

    private fun draftSlotKey(draftId: String): String = draftSlotKey(
        draftId.substringAfter("__grp__", "").substringBefore("__fld__"),
        draftId.substringAfter("__fld__", "").substringBefore("__grp__"),
    )

    internal fun isUnsentDraft(
        isDraft: Boolean,
        realSessionId: String,
        text: String,
        hasAttachments: Boolean,
        busy: Boolean,
    ): Boolean = isDraft && realSessionId.isEmpty() && !busy && (text.isNotBlank() || hasAttachments)

    @Synchronized
    fun newChatDraftId(groupId: String? = null, folderId: String? = null): String {
        val key = draftSlotKey(groupId, folderId)
        purgeExpiredDrafts(parkedDraftClock())
        parkedDrafts.remove(key)?.let { parked ->
            if (isResumableDraft(parked.draftId)) {
                Log.d(TAG, "newChatDraftId: resuming parked draft ${parked.draftId} slot=$key")
                return parked.draftId
            }
        }
        var id = "__new__${java.util.UUID.randomUUID()}"
        if (groupId != null) id += "__grp__$groupId"
        if (folderId != null) id += "__fld__$folderId"
        newChatDraftIds += id
        return id
    }

    private fun isResumableDraft(draftId: String): Boolean {
        if (aliases.containsKey(draftId)) return false
        val store = stores[draftId] ?: return false
        return viewModelIn(store)?.hasUnsentDraft ?: true
    }

    @Synchronized
    fun parkDraftIfUnsent(sessionId: String, hasUnsentDraft: Boolean) {
        if (sessionId !in newChatDraftIds) return
        val now = parkedDraftClock()
        purgeExpiredDrafts(now)
        val key = draftSlotKey(sessionId)
        val keep = hasUnsentDraft && stores.containsKey(sessionId) && !aliases.containsKey(sessionId)
        if (keep) {
            parkedDrafts[key] = ParkedDraft(sessionId, now)
            Log.d(TAG, "parkDraft $sessionId slot=$key keptFor=${PARKED_DRAFT_LIFETIME_MS / 1000}s")
        } else if (parkedDrafts[key]?.draftId == sessionId) {
            parkedDrafts.remove(key)
        }
    }

    @Synchronized
    fun unparkDraft(sessionId: String) {
        purgeExpiredDrafts(parkedDraftClock())
        parkedDrafts.entries.removeAll { it.value.draftId == sessionId }
    }

    private fun purgeExpiredDrafts(now: Long) {
        val expired = parkedDrafts.filterValues { now - it.parkedAtMs > PARKED_DRAFT_LIFETIME_MS }
        if (expired.isEmpty()) return
        expired.keys.forEach { parkedDrafts.remove(it) }
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        for (draft in expired.values) {
            val id = draft.draftId
            if (id != onScreen && id !in busy && !aliases.containsKey(id)) dropStoreKeepingChildTag(id)
        }
        Log.d(TAG, "purgeExpiredDrafts: dropped ${expired.size} parked draft(s)")
    }

    private fun forgetDraft(draftId: String) {
        if (newChatDraftIds.remove(draftId)) {
            parkedDrafts.entries.removeAll { it.value.draftId == draftId }
        }
    }

    @Synchronized
    internal fun clearParkedDraftsForTest() {
        parkedDrafts.clear()
        newChatDraftIds.clear()
    }

    data class PendingTransfer(
        val inputText: String,
        val attachments: List<InputAttachment>,
        val targetId: String,
        val stashedAtMs: Long = System.currentTimeMillis(),
    )

    private const val STASH_TTL_MS = 300_000L

    @Volatile
    private var pendingTransfer: PendingTransfer? = null

    fun stashPendingTransfer(transfer: PendingTransfer) {
        pendingTransfer = transfer
        Log.d(
            TAG,
            "stashPendingTransfer: target=${transfer.targetId} " +
                "text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
    }

    fun consumePendingTransfer(sessionId: String): PendingTransfer? {
        val t = pendingTransfer ?: return null
        if (System.currentTimeMillis() - t.stashedAtMs > STASH_TTL_MS) {
            pendingTransfer = null
            Log.d(TAG, "consumePendingTransfer: dropping stale stash (target=${t.targetId})")
            return null
        }
        if (resolveKey(t.targetId) != resolveKey(sessionId)) {
            Log.d(
                TAG,
                "consumePendingTransfer: session=$sessionId is not target=${t.targetId}, leaving stash",
            )
            return null
        }
        pendingTransfer = null
        Log.d(
            TAG,
            "consumePendingTransfer: target=${t.targetId} " +
                "text=${t.inputText.length}ch attachments=${t.attachments.size}",
        )
        return t
    }
}
