package com.openminis.app.sandbox

import java.io.InputStream
import java.nio.charset.StandardCharsets

internal class StderrDrain(
    private val stream: InputStream,
    private val log: (String) -> Unit,
) {
    private val kept = StringBuilder()
    private val thread = Thread({ drain() }, "FreshProcessShell-stderr").apply { isDaemon = true }

    fun start(): StderrDrain = apply { thread.start() }

    private fun drain() {
        try {
            stream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (isProotDebugLine(line)) log(line) else synchronized(kept) { kept.append(line).append('\n') }
            }
        } catch (_: Exception) {
        }
    }

    fun finish(waitMs: Long): String {
        runCatching { thread.join(waitMs) }
        runCatching { stream.close() }
        return synchronized(kept) { kept.toString() }
    }

    companion object {
        private val PROOT_DEBUG_PREFIXES = listOf("[native_offload] ", "[fake_netlink] ", "[xnet] ")

        fun isProotDebugLine(line: String): Boolean = PROOT_DEBUG_PREFIXES.any { line.startsWith(it) }
    }
}
