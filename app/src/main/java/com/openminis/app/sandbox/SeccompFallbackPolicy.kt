package com.openminis.app.sandbox

object SeccompFallbackPolicy {

    const val NO_SECCOMP_ENV = "PROOT_NO_SECCOMP"
    const val NO_SECCOMP_VALUE = "1"

    const val EARLY_DEATH_MS = 1_500L

    val RETRYABLE_EXIT_CODES = setOf(132, 135, 139, 159)

    fun shouldRetryWithoutSeccomp(
        exitCode: Int?,
        durationMs: Long,
        producedOutput: Boolean,
        alreadyRetried: Boolean,
    ): Boolean {
        if (alreadyRetried) return false
        if (producedOutput) return false
        if (exitCode == null || exitCode !in RETRYABLE_EXIT_CODES) return false
        return durationMs < EARLY_DEATH_MS
    }

    fun signalName(exitCode: Int?): String? = when (exitCode) {
        132 -> "SIGILL"
        135 -> "SIGBUS"
        139 -> "SIGSEGV"
        159 -> "SIGSYS"
        else -> null
    }

    fun retryLogLine(exitCode: Int?, durationMs: Long, what: String): String {
        val sig = signalName(exitCode) ?: "signal"
        return "[proot-retry] detected early $sig (exit=$exitCode) after ${durationMs}ms " +
            "in $what — retrying once with $NO_SECCOMP_ENV=$NO_SECCOMP_VALUE (GH#186)"
    }
}
