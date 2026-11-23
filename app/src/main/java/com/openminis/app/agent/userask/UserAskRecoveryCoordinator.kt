package com.openminis.app.agent.userask

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

interface UserAskRecords {
    fun all(): Map<String, UserAskRecord>
    fun record(requestId: String): UserAskRecord? = all()[requestId]
    fun save(r: UserAskRecord)
    fun remove(requestId: String)

    fun onAskChanged(ask: PendingAsk) {
        if (ask.consumed) { remove(ask.requestId); return }
        save((record(ask.requestId) ?: UserAskRecord(ask)).copy(ask = ask))
    }

    fun purge(nowMs: Long) {
        val all = all()
        val kept = UserAskRecovery.purge(all, nowMs)
        for (k in all.keys - kept.keys) remove(k)
    }
}

interface UserAskTranscript {
    suspend fun partsJsonRows(sessionId: String): List<String>
    suspend fun append(sessionId: String, role: String, partsJson: String)

    suspend fun sessionExists(sessionId: String): Boolean

    suspend fun replaceToolResult(sessionId: String, toolUseId: String, output: String): Boolean = false
}

data class UserAskDeferredDelivery(
    val sessionId: String,
    val toolUseId: String,
    val resultJson: String,
    val answerMessage: String?,
)

class UserAskRecordStore(context: Context) : UserAskRecords {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): Map<String, UserAskRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return obj.keys().asSequence().mapNotNull { k -> obj.optJSONObject(k)?.let(UserAskRecord::fromJson)?.let { k to it } }.toMap()
    }

    @Synchronized
    override fun save(r: UserAskRecord) {
        pendingChanges.remove(r.ask.requestId)
        write(all() + (r.ask.requestId to r))
    }

    @Synchronized
    override fun remove(requestId: String) {
        pendingChanges.remove(requestId)
        val map = all()
        if (requestId in map) write(map - requestId)
    }

    override fun onAskChanged(ask: PendingAsk) {
        val sync = ask.consumed || ask.state.isTerminal
        synchronized(this) { pendingChanges[ask.requestId] = ask }
        if (sync) flushPending() else writer.execute { runCatching { flushPending() } }
    }

    @Synchronized
    internal fun flushPending() {
        if (pendingChanges.isEmpty()) return
        val batch = pendingChanges.values.toList()
        pendingChanges.clear()
        val map = all().toMutableMap()
        var changed = false
        for (ask in batch) {
            val cur = map[ask.requestId] ?: continue
            if (ask.consumed) map.remove(ask.requestId) else map[ask.requestId] = cur.copy(ask = ask)
            changed = true
        }
        if (changed) write(map)
    }

    private val pendingChanges = LinkedHashMap<String, PendingAsk>()

    @Synchronized
    override fun purge(nowMs: Long) = write(UserAskRecovery.purge(all(), nowMs))

    private fun write(map: Map<String, UserAskRecord>) {
        if (map.isEmpty()) { prefs.edit().remove(KEY).commit(); return }
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v.toJson())
        prefs.edit().putString(KEY, obj.toString()).commit()
    }

    companion object {
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "UserAskRecordWriter").apply { isDaemon = true }
        }
        const val PREFS = "user_ask_records"
        const val KEY = "userAsk.records.v1"
    }
}

object UserAskRecoveryCoordinator {
    private const val TAG = "UserAsk"
    private val installed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            AppLogger.error(TAG, "[UserAsk] RECOVER step failed (contained): ${e.javaClass.simpleName}: ${e.message}")
        },
    )

    @Volatile private var store: UserAskRecords? = null
    @Volatile private var repo: UserAskTranscript? = null

    @Volatile private var onDelivered: ((sessionId: String, resume: Boolean) -> Unit)? = null

    @Volatile private var onDeferredDelivered: ((UserAskDeferredDelivery) -> Unit)? = null

    fun ensureInstalled(
        context: Context,
        repository: ChatRepository,
        delivered: (sessionId: String, resume: Boolean) -> Unit,
        deferredDelivered: (UserAskDeferredDelivery) -> Unit = {},
    ) {
        if (!installed.compareAndSet(false, true)) return
        install(UserAskRecordStore(context), ChatRepositoryTranscript(repository), delivered, scanOthers = true, deferredDelivered)
    }

    internal fun install(
        s: UserAskRecords,
        transcript: UserAskTranscript,
        delivered: (String, Boolean) -> Unit,
        scanOthers: Boolean,
        deferredDelivered: (UserAskDeferredDelivery) -> Unit = {},
    ) {
        installed.set(true)
        store = s
        repo = transcript
        onDelivered = delivered
        onDeferredDelivered = deferredDelivered
        UserAskCenter.onChanged = { ask -> runCatching { s.onAskChanged(ask) } }
        UserAskCenter.onResolvedWithoutWaiter = { ask -> scope.launch { deliverRecovered(ask.requestId) } }
        UserAskCenter.onDeferredResolved = { ask -> scope.launch { deliverDeferred(ask.requestId) } }
        if (scanOthers) scope.launch { recoverOthers() }
    }

    private class ChatRepositoryTranscript(private val r: ChatRepository) : UserAskTranscript {
        override suspend fun partsJsonRows(sessionId: String) = r.loadMessages(sessionId).map { it.partsJson }
        override suspend fun append(sessionId: String, role: String, partsJson: String) {
            r.appendMessage(sessionId, role, partsJson)
        }
        override suspend fun sessionExists(sessionId: String) = r.getSession(sessionId) != null
        override suspend fun replaceToolResult(sessionId: String, toolUseId: String, output: String): Boolean {
            for (m in r.loadMessages(sessionId)) {
                val patched = UserAskRecovery.replaceToolResultOutput(m.partsJson, toolUseId, output) ?: continue
                r.updateMessageParts(m.id, patched)
                return true
            }
            return false
        }
    }

    fun saveNew(record: UserAskRecord) {
        if (isDraftId(record.ask.sessionId)) {
            AppLogger.info(TAG, "[UserAsk] not persisting req=${record.ask.requestId.take(8)}: draft session")
            return
        }
        runCatching { store?.save(record) }
    }

    internal fun isDraftId(sessionId: String): Boolean = sessionId.startsWith("__new__")

    fun pendingToolCallIds(sessionId: String): Set<String> {
        val live = UserAskCenter.pendingAsks(sessionId).map { it.toolCallId }
        val stored = store?.all()?.values
            ?.filter { it.ask.sessionId == sessionId && it.ask.state == UserAskState.PENDING && !it.ask.consumed }
            ?.map { it.ask.toolCallId } ?: emptyList()
        return (live + stored).toSet()
    }

    suspend fun restoreForSession(sessionId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = store ?: return false
        val r = repo ?: return false
        var resume = false
        val deferredDone = mutableListOf<UserAskDeferredDelivery>()
        mutex.withLock {
            runCatching { s.purge(nowMs) }
            for ((rid, rec) in s.all()) {
                if (rec.ask.sessionId != sessionId || UserAskCenter.isInProcess(rid)) continue
                try {
                    if (isDraftId(sessionId) || !r.sessionExists(sessionId)) {
                        AppLogger.info(TAG, "[UserAsk] RECOVER req=${rid.take(8)} dropped: session ${sessionId.take(8)} does not exist")
                        s.remove(rid)
                        continue
                    }
                    resume = restoreOne(s, r, rid, rec, sessionId, nowMs, deferredDone) || resume
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "[UserAsk] RECOVER req=${rid.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                    runCatching { s.remove(rid) }
                }
            }
        }
        deferredDone.forEach { d -> onDeferredDelivered?.invoke(d) }
        return resume
    }

    private suspend fun restoreOne(
        s: UserAskRecords,
        r: UserAskTranscript,
        rid: String,
        rec: UserAskRecord,
        sessionId: String,
        nowMs: Long,
        deferredDone: MutableList<UserAskDeferredDelivery>,
    ): Boolean {
        val inStore = toolResultExists(r, sessionId, rec.ask.toolCallId)
        val deferredInStore = rec.ask.deferred && inStore
        return when (UserAskRecovery.action(rec, nowMs, inStore)) {
            UserAskRecoveryAction.ALREADY_DELIVERED -> { s.remove(rid); false }
            UserAskRecoveryAction.AWAIT_USER -> {
                if (UserAskCenter.asks.value[rid]?.state == UserAskState.PENDING) return false
                if (deferredInStore) {
                    UserAskCenter.begin(rec.ask, ownedByToolCall = false)
                    AppLogger.info(TAG, "[UserAsk] RECOVER awaiting deferred req=${rid.take(8)} - card restored")
                    return false
                }
                val persisted = ensureAssistantRow(r, rec)
                s.save(persisted)
                UserAskCenter.begin(persisted.ask, ownedByToolCall = false)
                AppLogger.info(TAG, "[UserAsk] RECOVER awaiting req=${rid.take(8)} - card restored, run continues on answer")
                false
            }
            UserAskRecoveryAction.TIME_OUT_THEN_DELIVER -> {
                val timed = rec.ask.resolveTimedOut() ?: rec.ask
                if (deferredInStore) {
                    deliverDeferredLocked(s, r, timed)?.let { deferredDone += it }
                    false
                } else deliverLocked(s, r, rec.copy(ask = timed))
            }
            UserAskRecoveryAction.DELIVER -> deliverLocked(s, r, rec)
            UserAskRecoveryAction.DELIVER_DEFERRED -> {
                deliverDeferredLocked(s, r, UserAskCenter.asks.value[rid] ?: rec.ask)?.let { deferredDone += it }
                false
            }
        }
    }

    private val supersedingInline = java.util.Collections.synchronizedSet(HashSet<String>())

    suspend fun supersedeRecovered(sessionId: String): List<com.openminis.app.data.model.AgentContentPart.ToolResult>? {
        val s = store ?: return null
        val r = repo ?: return null
        val restored = UserAskCenter.pendingAsks(sessionId).filter { !UserAskCenter.isInProcess(it.requestId) && !it.deferred }
        if (restored.isEmpty()) return null
        val parts = mutableListOf<com.openminis.app.data.model.AgentContentPart.ToolResult>()
        for (a in restored) {
            supersedingInline += a.requestId
            try {
                UserAskCenter.dismiss(a.requestId, UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)
                try { mutex.withLock {
                    val rec = s.record(a.requestId) ?: return@withLock
                    val live = UserAskCenter.asks.value[a.requestId] ?: return@withLock
                    val json = live.resultJson ?: return@withLock
                    if (!r.sessionExists(live.sessionId) || toolResultExists(r, live.sessionId, live.toolCallId)) {
                        s.remove(a.requestId)
                        return@withLock
                    }
                    deliverLocked(s, r, rec.copy(ask = live))
                    val order = UserAskRecovery.toolUseOrder(rec.assistantPartsJson).ifEmpty { listOf(live.toolCallId to UserAskPromptText.TOOL_NAME) }
                    for ((id, name) in order) {
                        val mine = id == live.toolCallId
                        parts += com.openminis.app.data.model.AgentContentPart.ToolResult(
                            id = id, name = name, content = if (mine) json else UserAskRecovery.INTERRUPTED_RESULT, isError = !mine,
                        )
                    }
                } } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "[UserAsk] supersede req=${a.requestId.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                    runCatching { s.remove(a.requestId) }
                }
            } finally {
                supersedingInline -= a.requestId
            }
        }
        return parts.ifEmpty { null }
    }

    suspend fun deliverRecovered(requestId: String) {
        val s = store ?: return
        val r = repo ?: return
        if (requestId in supersedingInline) return
        var sid: String? = null
        var resume = false
        mutex.withLock {
            val rec = s.record(requestId) ?: return
            val live = UserAskCenter.asks.value[requestId] ?: rec.ask
            if (!live.state.isTerminal || live.resultJson == null) return
            sid = live.sessionId
            resume = try {
                deliverLocked(s, r, rec.copy(ask = live))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[UserAsk] RECOVER deliver req=${requestId.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { s.remove(requestId) }
                return
            }
        }
        sid?.let { onDelivered?.invoke(it, resume) }
    }

    suspend fun deliverDeferred(requestId: String) {
        val s = store ?: return
        val r = repo ?: return
        var done: UserAskDeferredDelivery? = null
        mutex.withLock {
            val ask = UserAskCenter.asks.value[requestId] ?: s.record(requestId)?.ask ?: return
            done = try {
                deliverDeferredLocked(s, r, ask)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[UserAsk] DEFERRED deliver req=${requestId.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { s.remove(requestId) }
                null
            }
        }
        done?.let { onDeferredDelivered?.invoke(it) }
    }

    private suspend fun deliverDeferredLocked(s: UserAskRecords, r: UserAskTranscript, ask: PendingAsk): UserAskDeferredDelivery? {
        if (!ask.deferred || !ask.state.isTerminal || ask.consumed) return null
        val json = ask.resultJson ?: return null
        val rec = s.record(ask.requestId)
        if (UserAskCenter.asks.value[ask.requestId] != null && !UserAskCenter.markDeferredConsumed(ask.requestId)) return null
        s.remove(ask.requestId)
        val sid = ask.sessionId
        if (isDraftId(sid) || !r.sessionExists(sid)) {
            AppLogger.info(TAG, "[UserAsk] DEFERRED req=${ask.requestId.take(8)} dropped: session ${sid.take(8)} does not exist")
            return null
        }
        if (!r.replaceToolResult(sid, ask.toolCallId, json)) {
            if (rec != null) deliverLocked(s, r, rec.copy(ask = ask.copy(consumed = false)))
        }
        val message = if (UserAskDeferred.sendsAnswer(ask)) UserAskDeferred.answerMessage(ask) else null
        AppLogger.info(
            TAG,
            "[UserAsk] DEFERRED req=${ask.requestId.take(8)} state=${ask.state.wire} statuses=${ask.resultStatuses.map { it.wire }} " +
                "- result filled in, message=${message != null}",
        )
        return UserAskDeferredDelivery(sid, ask.toolCallId, json, message)
    }

    private suspend fun recoverOthers() {
        val s = store ?: return
        val sessions = s.all().values.map { it.ask.sessionId }.toSet()
        for (sid in sessions) {
            val resume = restoreForSession(sid)
            if (resume) onDelivered?.invoke(sid, true)
        }
    }

    private suspend fun deliverLocked(s: UserAskRecords, r: UserAskTranscript, rec: UserAskRecord): Boolean {
        val ask = rec.ask
        val json = ask.resultJson ?: return false
        val sid = ask.sessionId
        if (isDraftId(sid) || !r.sessionExists(sid)) {
            AppLogger.info(TAG, "[UserAsk] RECOVER req=${ask.requestId.take(8)} dropped: session ${sid.take(8)} does not exist")
            s.remove(ask.requestId)
            return false
        }
        if (toolResultExists(r, sid, ask.toolCallId)) {
            s.remove(ask.requestId)
            UserAskCenter.markDelivered(listOf(ask.toolCallId))
            return false
        }
        val snapshot = rec.assistantPartsJson
        if (snapshot.isNullOrEmpty() && !rec.assistantPersisted && !toolUseExists(r, sid, ask.toolCallId)) {
            AppLogger.warning(TAG, "[UserAsk] RECOVER req=${ask.requestId.take(8)} - assistant snapshot missing, falling back to a plain message")
            r.append(sid, "user", JSONArray().put(JSONObject().put("type", "text").put("value", UserAskRecovery.plainTextFallback(ask))).toString())
        } else {
            if (!rec.assistantPersisted && !toolUseExists(r, sid, ask.toolCallId) && snapshot != null) {
                r.append(sid, "assistant", snapshot)
            }
            val order = UserAskRecovery.toolUseOrder(snapshot).ifEmpty { listOf(ask.toolCallId to UserAskPromptText.TOOL_NAME) }
            r.append(sid, "user", UserAskRecovery.resultRowPartsJson(ask, json, order))
        }
        s.remove(ask.requestId)
        UserAskCenter.markDelivered(listOf(ask.toolCallId))
        val resume = UserAskRecovery.resumesRun(ask)
        AppLogger.info(
            TAG,
            "[UserAsk] RECOVER delivered req=${ask.requestId.take(8)} state=${ask.state.wire} statuses=${ask.resultStatuses.map { it.wire }} resume=$resume",
        )
        return resume
    }

    private suspend fun ensureAssistantRow(r: UserAskTranscript, rec: UserAskRecord): UserAskRecord {
        if (rec.assistantPersisted) return rec
        val snapshot = rec.assistantPartsJson ?: return rec
        if (!toolUseExists(r, rec.ask.sessionId, rec.ask.toolCallId)) r.append(rec.ask.sessionId, "assistant", snapshot)
        return rec.copy(assistantPersisted = true)
    }

    private suspend fun toolResultExists(r: UserAskTranscript, sid: String, toolUseId: String): Boolean =
        r.partsJsonRows(sid).any { partHasId(it, "toolResult", toolUseId) }

    private suspend fun toolUseExists(r: UserAskTranscript, sid: String, toolUseId: String): Boolean =
        r.partsJsonRows(sid).any { partHasId(it, "toolUse", toolUseId) }

    internal fun partHasId(partsJson: String, type: String, toolUseId: String): Boolean {
        if (!partsJson.contains(toolUseId)) return false
        val arr = runCatching { JSONArray(partsJson) }.getOrNull() ?: return false
        return (0 until arr.length()).any { i ->
            val o = arr.optJSONObject(i)
            o?.optString("type") == type && o.optJSONObject("value")?.optString("toolUseId") == toolUseId
        }
    }

    internal fun resetForTest() {
        installed.set(false)
        store = null
        repo = null
        onDelivered = null
        onDeferredDelivered = null
    }
}
