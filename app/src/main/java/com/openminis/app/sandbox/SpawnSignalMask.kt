package com.openminis.app.sandbox

import android.util.Log

internal object SpawnSignalMask {
    private const val TAG = "SpawnSignalMask"

    const val WAS_BLOCKED = 1
    const val DISPOSITION_IGNORED = 2

    internal interface Native {
        fun unblockSigpipe(): Int

        fun blockSigpipe(): Int
    }

    private object PtyNative : Native {
        override fun unblockSigpipe(): Int = PtyBridge.unblockSigpipe()
        override fun blockSigpipe(): Int = PtyBridge.blockSigpipe()
    }

    @Volatile
    internal var native: Native = PtyNative

    @Volatile
    private var ignoredLogged = false

    fun start(pb: ProcessBuilder): Process = withSigpipeUnblocked { pb.start() }

    fun <T> withSigpipeUnblocked(block: () -> T): T {
        val state = try {
            native.unblockSigpipe()
        } catch (t: Throwable) {
            Log.w(TAG, "SIGPIPE unblock unavailable: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
        if (state < 0) Log.w(TAG, "SIGPIPE unblock failed ($state); spawning with the inherited mask")
        if (state >= 0 && state and DISPOSITION_IGNORED != 0 && !ignoredLogged) {
            ignoredLogged = true
            Log.w(TAG, "SIGPIPE is SIG_IGN in this process; children inherit it and `yes | head` can still hang")
        }
        try {
            return block()
        } finally {
            if (state >= 0 && state and WAS_BLOCKED != 0) {
                val rc = runCatching { native.blockSigpipe() }.getOrElse { -1 }
                if (rc != 0) Log.e(TAG, "SIGPIPE re-block failed ($rc)")
            }
        }
    }
}
