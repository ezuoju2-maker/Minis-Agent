package com.openminis.app.hostfrail

import java.util.Locale

data class NativeObservation(
    val debuggerAttached: Boolean,
    val tracerPid: Int,
    val selfThread: String,
    val bridges: List<String>,
    val implications: List<String>,
    val flagged: Boolean,
)

object NativeProbe {

    private const val LIB = "frailhost"

    @Volatile
    private var libraryLoaded: Boolean = false

    @Volatile
    private var loadFailure: String? = null

    @Volatile
    private var trampoline: ((Boolean, Int, List<String>, List<String>) -> Unit)? = null

    fun load(): Boolean {
        if (libraryLoaded) return true
        synchronized(this) {
            if (libraryLoaded) return true
            val attempted = runCatching {
                System.loadLibrary(LIB)
                true
            }.getOrElse { t ->
                loadFailure = t.javaClass.simpleName + ":" + (t.message ?: "?")
                false
            }
            libraryLoaded = attempted
            return attempted
        }
    }

    fun available(): Boolean = libraryLoaded

    fun loadFailureReason(): String? = loadFailure

    fun attach(sink: (debugger: Boolean, tracer: Int, bridges: List<String>, implications: List<String>) -> Unit) {
        trampoline = sink
    }

    fun detach() {
        trampoline = null
    }

    @JvmStatic
    fun onNativeObservation(
        debugger: Boolean,
        tracer: Int,
        selfThread: String?,
        bridges: Array<String>?,
        implicationCount: Int,
        implications: Array<String>?,
    ) {
        // 由 native 线程回调：只落进原子快照，不在这里做耗时工作，也不碰 UI。
        val b = bridges?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val i = if (implicationCount > 0) {
            implications?.take(implicationCount)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        } else {
            emptyList()
        }
        OBSERVATION.publish(debugger, tracer, selfThread ?: "", b, i)
        trampoline?.invoke(debugger, tracer, b, i)
    }

    internal object OBSERVATION {
        @Volatile var debugger: Boolean = false
        @Volatile var tracer: Int = -1
        @Volatile var selfThread: String = ""
        @Volatile var bridges: List<String> = emptyList()
        @Volatile var implications: List<String> = emptyList()

        fun publish(dbg: Boolean, tr: Int, self: String, br: List<String>, imp: List<String>) {
            debugger = dbg
            tracer = tr
            selfThread = self
            bridges = br
            implications = imp
        }

        fun reset() {
            publish(false, -1, "", emptyList(), emptyList())
        }

        fun snapshot(flagged: Boolean): NativeObservation = NativeObservation(
            debuggerAttached = debugger,
            tracerPid = tracer,
            selfThread = selfThread,
            bridges = bridges,
            implications = implications,
            flagged = flagged || debugger || bridges.isNotEmpty(),
        )
    }

    fun observeNow(): NativeObservation? {
        if (!load()) return null
        OBSERVATION.reset()
        val flagged = runCatching { nativeProbe() }.getOrDefault(false)
        return OBSERVATION.snapshot(flagged)
    }

    fun currentThreadName(): String = runCatching {
        nativeThreadName()
    }.getOrDefault("")

    fun selfMapsDigest(): String? = runCatching {
        nativeMapsDigest()
    }.getOrNull()

    private external fun nativeProbe(): Boolean

    private external fun nativeThreadName(): String

    private external fun nativeMapsDigest(): String

    private external fun nativeTimingSample(): Long

    private external fun nativeStatusBlob(): String

    fun timingSample(): Long? {
        if (!load()) return null
        return runCatching { nativeTimingSample() }.getOrNull()
    }

    fun statusBlob(): String? {
        if (!load()) return null
        return runCatching { nativeStatusBlob() }.getOrNull()
    }

    /** 采样标准差：hooks 与 ptrace-stop 会把抖动压成接近常数或放大若干数量级。 */
    fun timingJitterSamples(count: Int = 12): List<Long> {
        if (!load()) return emptyList()
        val out = ArrayList<Long>(count)
        repeat(count) {
            val v = runCatching { nativeTimingSample() }.getOrDefault(-1L)
            if (v >= 0) out.add(v)
        }
        return out
    }

    fun jitterCoefficient(samples: List<Long>): Double {
        if (samples.size < 3) return 0.0
        val mean = samples.sum().toDouble() / samples.size
        if (mean <= 0.0) return 0.0
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        return kotlin.math.sqrt(variance) / mean
    }

    fun describe(): String = buildString {
        append("native=").append(if (libraryLoaded) "loaded" else "absent")
        if (loadFailure != null) append(" reason=").append(loadFailure)
        append(" observer=").append(if (trampoline != null) "bound" else "idle")
        append(" abi=").append((android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?").lowercase(Locale.ROOT))
    }
}
