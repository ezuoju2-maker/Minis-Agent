package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BackgroundInterruptionTracker {

    private var backgroundTimestampMs: Long? = null
    private var wasSessionActive = false

    private val _interrupted = MutableStateFlow(false)
    val interrupted: StateFlow<Boolean> = _interrupted.asStateFlow()

    private val _interruptionDurationMs = MutableStateFlow(0L)
    val interruptionDurationMs: StateFlow<Long> = _interruptionDurationMs.asStateFlow()

    fun onBackground(sessionActive: Boolean) {
        backgroundTimestampMs = System.currentTimeMillis()
        wasSessionActive = sessionActive
    }

    fun onForeground() {
        val bgTs = backgroundTimestampMs ?: return
        if (wasSessionActive) {
            val duration = System.currentTimeMillis() - bgTs
            if (duration > 5_000) {
                _interruptionDurationMs.value = duration
                _interrupted.value = true
            }
        }
        backgroundTimestampMs = null
        wasSessionActive = false
    }

    fun acknowledge() {
        _interrupted.value = false
        _interruptionDurationMs.value = 0
    }
}
