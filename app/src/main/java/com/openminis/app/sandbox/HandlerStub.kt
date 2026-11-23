package com.openminis.app.sandbox

internal object HandlerStub {
    const val LEGACY_CONTENT = "#!/bin/sh\nexit 0\n"

    const val EXIT_UNAVAILABLE = 69

    fun content(name: String): String =
        "#!/bin/sh\n" +
            "echo \"$name: unavailable - DeepSeek could not start its native offload service. " +
            "Restart the Minis app and try again.\" >&2\n" +
            "exit $EXIT_UNAVAILABLE\n"
}
