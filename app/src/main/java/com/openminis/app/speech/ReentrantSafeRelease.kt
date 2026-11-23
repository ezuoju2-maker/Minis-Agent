package com.openminis.app.speech

internal class ReentrantSafeRelease<T : Any>(private val release: (T) -> Unit) {
    private val lock = Any()
    private var live: T? = null
    private var inCall = false
    private var deferred: T? = null

    fun attach(value: T) = synchronized(lock) { live = value }

    fun call(block: (T) -> Unit): Boolean = synchronized(lock) {
        val target = live ?: return false
        inCall = true
        try {
            block(target)
        } finally {
            inCall = false
            deferred?.let { d ->
                deferred = null
                runCatching { release(d) }
            }
        }
        live != null
    }

    fun tearDown() {
        synchronized(lock) {
            val target = live ?: return
            live = null
            if (inCall) {
                deferred = target
            } else {
                runCatching { release(target) }
            }
        }
    }
}
