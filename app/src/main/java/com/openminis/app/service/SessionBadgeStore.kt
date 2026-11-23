package com.openminis.app.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionBadgeStore {
    private const val TAG = "SessionBadgeStore"
    private const val PREFS = "session_badge_store"

    private const val KEY = "badge_state_by_session"

    private const val KEY_V2 = "badge_state_by_session_v2"

    private const val NO_STAMP = 0L

    enum class SessionBadgeState {
        PAUSED,
        ICLOUD_SYNCING,
    }

    @Volatile private var prefs: SharedPreferences? = null

    private val _byId = MutableStateFlow<Map<String, List<SessionBadgeState>>>(emptyMap())
    val byId: StateFlow<Map<String, List<SessionBadgeState>>> = _byId.asStateFlow()

    @Volatile
    private var stamps: Map<String, Map<SessionBadgeState, Long>> = emptyMap()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val (restored, restoredStamps) = loadFromDisk(p)
        synchronized(this) {
            _byId.value = restored
            stamps = restoredStamps
        }
        Log.d(TAG, "init: restored ${restored.size} sessions with badges, ${restoredStamps.size} stamped")
    }

    fun headFor(sessionId: String): SessionBadgeState? =
        _byId.value[sessionId]?.firstOrNull()

    fun push(sessionId: String, state: SessionBadgeState, restamp: Boolean = true) {
        mutate { current ->
            val q = current[sessionId].orEmpty()
            val existing = stamps[sessionId]?.get(state)
            if (restamp || existing == null) {
                putStamp(sessionId, state, System.currentTimeMillis())
            }
            if (q.firstOrNull() == state) {
                return@mutate if (restamp || existing == null) HashMap(current) else current
            }
            val deduped = q.filter { it != state }
            current + (sessionId to (listOf(state) + deduped))
        }
    }

    fun remove(sessionId: String, state: SessionBadgeState) {
        mutate { current ->
            val q = current[sessionId] ?: return@mutate current
            val updated = q.filter { it != state }
            dropStamp(sessionId, state)
            if (updated.isEmpty()) current - sessionId else current + (sessionId to updated)
        }
    }

    fun clear(sessionId: String) {
        mutate { current ->
            if (!current.containsKey(sessionId)) return@mutate current
            if (sessionId in stamps) {
                stamps = stamps - sessionId
                _revision.value += 1
            }
            current - sessionId
        }
    }

    fun freshCornerBadgeSessionIds(windowMillis: Long): Set<String> {
        val cutoff = System.currentTimeMillis() - windowMillis
        val states = _byId.value
        val ts = stamps
        if (states.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for ((sid, queue) in states) {
            val perState = ts[sid] ?: continue
            if (queue.any { st -> (perState[st] ?: Long.MIN_VALUE) >= cutoff }) {
                out.add(sid)
            }
        }
        return out
    }

    fun reconcileInterruptedSessions(interruptedIds: Set<String>) {
        mutate { current ->
            val next = current.toMutableMap()
            for (sid in interruptedIds) {
                val q = next[sid].orEmpty()
                if (q.firstOrNull() != SessionBadgeState.PAUSED && !q.contains(SessionBadgeState.PAUSED)) {
                    next[sid] = listOf(SessionBadgeState.PAUSED) + q
                    if (stamps[sid]?.get(SessionBadgeState.PAUSED) == null) {
                        putStamp(sid, SessionBadgeState.PAUSED, System.currentTimeMillis())
                    }
                }
            }
            val iterIds = next.keys.toList()
            for (sid in iterIds) {
                val q = next[sid] ?: continue
                if (q.contains(SessionBadgeState.PAUSED) && !interruptedIds.contains(sid)) {
                    val updated = q.filter { it != SessionBadgeState.PAUSED }
                    dropStamp(sid, SessionBadgeState.PAUSED)
                    if (updated.isEmpty()) next.remove(sid) else next[sid] = updated
                }
            }
            next
        }
        Log.d(TAG, "reconcileInterruptedSessions: ${interruptedIds.size} interrupted")
    }


    private fun putStamp(sessionId: String, state: SessionBadgeState, at: Long) {
        val perState = stamps[sessionId].orEmpty() + (state to at)
        stamps = stamps + (sessionId to perState)
        _revision.value += 1
    }

    private fun dropStamp(sessionId: String, state: SessionBadgeState) {
        val perState = stamps[sessionId] ?: return
        if (state !in perState) return
        val updated = perState - state
        stamps = if (updated.isEmpty()) stamps - sessionId else stamps + (sessionId to updated)
        _revision.value += 1
    }

    private fun mutate(block: (Map<String, List<SessionBadgeState>>) -> Map<String, List<SessionBadgeState>>) {
        synchronized(this) {
            val prev = _byId.value
            val next = block(prev)
            if (next === prev) return
            _byId.value = next
            prefs?.let { persistToDisk(it, next) }
        }
    }

    private fun persistToDisk(p: SharedPreferences, map: Map<String, List<SessionBadgeState>>) {
        val ts = stamps
        val encoded = map.entries.joinToString(";") { (id, q) ->
            val perState = ts[id]
            "$id=" + q.joinToString(",") { "${it.name}:${perState?.get(it) ?: NO_STAMP}" }
        }
        runCatching { p.edit().putString(KEY_V2, encoded).apply() }
            .onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    private fun loadFromDisk(
        p: SharedPreferences,
    ): Pair<Map<String, List<SessionBadgeState>>, Map<String, Map<SessionBadgeState, Long>>> {
        val rawV2 = runCatching { p.getString(KEY_V2, null) }.getOrNull()
        if (!rawV2.isNullOrBlank()) return decodeV2(rawV2)
        val rawV1 = runCatching { p.getString(KEY, null) }.getOrNull()
        if (rawV1.isNullOrBlank()) return emptyMap<String, List<SessionBadgeState>>() to emptyMap()
        val migrated = decodeV1(rawV1)
        Log.d(TAG, "loadFromDisk: migrated ${migrated.size} sessions from v1 (unstamped → stale)")
        return migrated to emptyMap()
    }

    private fun decodeV2(
        raw: String,
    ): Pair<Map<String, List<SessionBadgeState>>, Map<String, Map<SessionBadgeState, Long>>> {
        val queues = LinkedHashMap<String, List<SessionBadgeState>>()
        val ts = LinkedHashMap<String, Map<SessionBadgeState, Long>>()
        for (entry in raw.split(';')) {
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.lastIndex) continue
            val id = entry.substring(0, eq)
            val q = mutableListOf<SessionBadgeState>()
            val perState = LinkedHashMap<SessionBadgeState, Long>()
            for (token in entry.substring(eq + 1).split(',')) {
                val colon = token.lastIndexOf(':')
                val name = if (colon > 0) token.substring(0, colon) else token
                val state = runCatching { SessionBadgeState.valueOf(name) }.getOrNull() ?: continue
                q.add(state)
                val at = if (colon > 0) token.substring(colon + 1).toLongOrNull() ?: NO_STAMP else NO_STAMP
                if (at != NO_STAMP) perState[state] = at
            }
            if (q.isEmpty()) continue
            queues[id] = q
            if (perState.isNotEmpty()) ts[id] = perState
        }
        return queues to ts
    }

    private fun decodeV1(raw: String): Map<String, List<SessionBadgeState>> =
        raw.split(';').mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.lastIndex) return@mapNotNull null
            val id = entry.substring(0, eq)
            val q = entry.substring(eq + 1)
                .split(',')
                .mapNotNull { name -> runCatching { SessionBadgeState.valueOf(name) }.getOrNull() }
            if (q.isEmpty()) null else id to q
        }.toMap()
}
