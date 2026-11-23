package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.openminis.app.logging.AppLogger

class ReadAloudAudioFocus internal constructor(
    private val gateway: Gateway,
    private val scheduler: Scheduler,
    private val graceMs: Long = GRACE_MS,
    private val maxHoldMs: Long = MAX_HOLD_MS,
    private val log: (String) -> Unit = { AppLogger.info(TAG, it) },
) {

    interface Gateway {
        var onFocusChange: (Int) -> Unit

        fun request(): Int
        fun abandon()
    }

    fun interface Scheduler {
        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
    }

    private class Owner(var onLost: () -> Unit) {
        var cancelGrace: (() -> Unit)? = null
    }

    private val owners = LinkedHashMap<Any, Owner>()
    private var cancelWatchdog: (() -> Unit)? = null

    var isHeld: Boolean = false
        private set

    val ownerCount: Int get() = owners.size

    init {
        gateway.onFocusChange = ::onFocusChange
    }

    fun acquire(owner: Any, onLost: () -> Unit) {
        val entry = owners.getOrPut(owner) { Owner(onLost) }
        entry.onLost = onLost
        entry.cancelGrace?.invoke()
        entry.cancelGrace = null
        armWatchdog()
        if (isHeld) return
        val rc = gateway.request()
        isHeld = rc == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log("request MAY_DUCK -> ${if (isHeld) "granted" else "not granted ($rc), playing anyway"} owners=${owners.size}")
    }

    fun releaseSoon(owner: Any, delayMs: Long = graceMs) {
        val entry = owners[owner] ?: return
        entry.cancelGrace?.invoke()
        entry.cancelGrace = scheduler.schedule(delayMs) {
            if (owners[owner] === entry) {
                entry.cancelGrace = null
                remove(owner, "idle ${delayMs}ms")
            }
        }
    }

    fun releaseNow(owner: Any) {
        val entry = owners[owner] ?: return
        entry.cancelGrace?.invoke()
        remove(owner, "released")
    }

    private fun remove(owner: Any, reason: String) {
        owners.remove(owner)
        if (owners.isEmpty()) abandon(reason)
    }

    private fun abandon(reason: String) {
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        owners.values.forEach { it.cancelGrace?.invoke() }
        owners.clear()
        if (!isHeld) return
        isHeld = false
        gateway.abandon()
        log("abandon ($reason)")
    }

    private fun armWatchdog() {
        cancelWatchdog?.invoke()
        cancelWatchdog = scheduler.schedule(maxHoldMs) {
            cancelWatchdog = null
            log("watchdog: no speech started for ${maxHoldMs}ms with focus held — forcing release")
            abandon("watchdog")
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log("focus lost ($change) — stopping read-aloud")
                val lost = owners.values.map { it.onLost }
                lost.forEach { runCatching { it() } }
                abandon("lost $change")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> log("transient duck request — continuing")
            else -> Unit
        }
    }

    private class PlatformGateway(context: Context) : Gateway {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        override var onFocusChange: (Int) -> Unit = {}

        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener({ onFocusChange(it) }, Handler(Looper.getMainLooper()))
            .build()

        override fun request(): Int = audioManager.requestAudioFocus(request)
        override fun abandon() {
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    companion object {
        private const val TAG = "ReadAloudFocus"

        const val GRACE_MS = 2_500L

        const val SYNTH_WAIT_MS = 8_000L

        const val MAX_HOLD_MS = 5 * 60_000L

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        @Volatile
        private var shared: ReadAloudAudioFocus? = null

        fun shared(context: Context): ReadAloudAudioFocus =
            shared ?: synchronized(this) {
                shared ?: run {
                    val main = Handler(Looper.getMainLooper())
                    ReadAloudAudioFocus(
                        gateway = PlatformGateway(context.applicationContext),
                        scheduler = { delayMs, block ->
                            val r = Runnable(block)
                            main.postDelayed(r, delayMs)
                            ({ main.removeCallbacks(r) })
                        },
                    )
                }.also { shared = it }
            }
    }
}
