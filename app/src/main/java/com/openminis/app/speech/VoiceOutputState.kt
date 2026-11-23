package com.openminis.app.speech

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VoiceOutputState {

    private const val PREFS = "voice_prefs"
    private const val KEY_ENABLED = "readReplies"
    private const val KEY_MUTED = "readReplies.muted"
    private const val KEY_SPEED = "readReplies.speed"
    private const val KEY_VOLUME = "readReplies.volume"
    private const val KEY_SYSTEM_VOICE = "readReplies.systemVoice"
    private const val KEY_SYSTEM_VOICE_LABEL = "readReplies.systemVoiceLabel"

    private const val KEY_DRAG_X_DP = "readReplies.capsuleDragXDp"
    private const val KEY_DRAG_Y_DP = "readReplies.capsuleDragYDp"

    val SPEED_STEPS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _volume = MutableStateFlow(DEFAULT_VOLUME)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    const val DEFAULT_VOLUME = 1.0f
    const val MAX_VOLUME = 2.0f

    fun clampVolume(v: Float): Float = if (v.isNaN()) DEFAULT_VOLUME else v.coerceIn(0f, MAX_VOLUME)

    private val _dragOffsetDp = MutableStateFlow<Pair<Float, Float>?>(null)
    val dragOffsetDp: StateFlow<Pair<Float, Float>?> = _dragOffsetDp.asStateFlow()

    val isSpeaking = MutableStateFlow(false)
    val isSynthesizing = MutableStateFlow(false)

    val activeModelLabel = MutableStateFlow<String?>(null)

    private val _systemVoiceName = MutableStateFlow<String?>(null)
    val systemVoiceName: StateFlow<String?> = _systemVoiceName.asStateFlow()

    private val _systemVoiceLabel = MutableStateFlow<String?>(null)
    val systemVoiceLabel: StateFlow<String?> = _systemVoiceLabel.asStateFlow()

    fun setSystemVoice(name: String?, label: String?) {
        _systemVoiceName.value = name
        _systemVoiceLabel.value = label
        prefs?.edit()
            ?.putString(KEY_SYSTEM_VOICE, name)
            ?.putString(KEY_SYSTEM_VOICE_LABEL, label)
            ?.apply()
    }

    private val players = ArrayDeque<ReadAloudPlayer>()

    val activePlayer: ReadAloudPlayer?
        get() = synchronized(players) { players.lastOrNull() }

    fun registerPlayer(player: ReadAloudPlayer) = synchronized(players) {
        players.remove(player)
        players.addLast(player)
    }

    fun unregisterPlayer(player: ReadAloudPlayer) = synchronized(players) {
        players.remove(player)
    }

    fun isActivePlayer(player: ReadAloudPlayer): Boolean =
        synchronized(players) { players.lastOrNull() === player }

    val canPlay: Boolean get() = _isEnabled.value && !_isMuted.value

    fun suspendAllForCapture() {
        val live = synchronized(players) { players.toList() }
        live.forEach { runCatching { it.suspendForCapture() } }
    }

    fun resumeAllAfterCapture() {
        val live = synchronized(players) { players.toList() }
        live.forEach { runCatching { it.resumeAfterCapture() } }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _isEnabled.value = p.getBoolean(KEY_ENABLED, false)
        _isMuted.value = p.getBoolean(KEY_MUTED, false)
        _speed.value = p.getFloat(KEY_SPEED, 1.0f)
        _volume.value = clampVolume(p.getFloat(KEY_VOLUME, DEFAULT_VOLUME))
        _systemVoiceName.value = p.getString(KEY_SYSTEM_VOICE, null)
        _systemVoiceLabel.value = p.getString(KEY_SYSTEM_VOICE_LABEL, null)
        _dragOffsetDp.value =
            if (p.contains(KEY_DRAG_X_DP) && p.contains(KEY_DRAG_Y_DP)) {
                p.getFloat(KEY_DRAG_X_DP, 0f) to p.getFloat(KEY_DRAG_Y_DP, 0f)
            } else {
                null
            }
    }

    fun setCapsuleDragOffsetDp(xDp: Float, yDp: Float) {
        _dragOffsetDp.value = xDp to yDp
        prefs?.edit()
            ?.putFloat(KEY_DRAG_X_DP, xDp)
            ?.putFloat(KEY_DRAG_Y_DP, yDp)
            ?.apply()
    }

    fun setEnabled(value: Boolean) {
        if (_isEnabled.value == value) return
        _isEnabled.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        if (!value) {
            activePlayer?.stop()
            setMuted(false)
        }
    }

    fun activateForExplicitReadAloud() {
        if (!_isEnabled.value) setEnabled(true)
        if (_isMuted.value) setMuted(false)
    }

    fun setMuted(value: Boolean) {
        if (_isMuted.value == value) return
        _isMuted.value = value
        prefs?.edit()?.putBoolean(KEY_MUTED, value)?.apply()
        if (value) activePlayer?.stop()
    }

    fun setSpeed(value: Float) {
        if (_speed.value == value) return
        _speed.value = value
        prefs?.edit()?.putFloat(KEY_SPEED, value)?.apply()
    }

    fun setVolume(value: Float, persist: Boolean = true) {
        _volume.value = clampVolume(value)
        if (persist) persistVolume()
    }

    fun persistVolume() {
        prefs?.edit()?.putFloat(KEY_VOLUME, _volume.value)?.apply()
    }

    fun nextSpeed() {
        val idx = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - _speed.value) < 0.001f }
        setSpeed(SPEED_STEPS[(if (idx < 0) 0 else idx + 1) % SPEED_STEPS.size])
    }

    fun speedLabel(s: Float = _speed.value): String =
        if (s == s.toInt().toFloat()) "${s.toInt()}×" else "$s×"
}
