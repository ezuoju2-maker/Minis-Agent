package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionUnreadStore {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    private val viewers = LinkedHashMap<Any, () -> String>()

    fun isViewing(sessionId: String): Boolean = synchronized(this) {
        viewers.values.any { it() == sessionId }
    }

    fun startViewing(token: Any, sessionId: () -> String) {
        synchronized(this) { viewers[token] = sessionId }
        markRead(sessionId())
    }

    fun stopViewing(token: Any) {
        synchronized(this) { viewers.remove(token) }
    }

    fun markUnread(sessionId: String) {
        if (sessionId.isEmpty() || isViewing(sessionId)) return
        _ids.update { it + sessionId }
    }

    fun markRead(sessionId: String) {
        if (sessionId.isEmpty()) return
        _ids.update { it - sessionId }
    }

    private inline fun MutableStateFlow<Set<String>>.update(f: (Set<String>) -> Set<String>) {
        synchronized(this@SessionUnreadStore) {
            val next = f(value)
            if (next != value) value = next
        }
    }
}
