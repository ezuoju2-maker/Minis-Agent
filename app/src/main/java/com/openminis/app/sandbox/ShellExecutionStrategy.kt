package com.openminis.app.sandbox

internal enum class ShellExecutionStrategy {
    WARM_SHELL,

    FRESH_PROCESS,

    ;

    companion object {
        val DEFAULT: ShellExecutionStrategy = FRESH_PROCESS

        fun needsInBandMarker(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL

        fun recyclesShellOnTimeout(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL
    }
}
