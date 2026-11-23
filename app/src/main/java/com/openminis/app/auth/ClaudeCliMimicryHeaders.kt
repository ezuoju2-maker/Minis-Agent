package com.openminis.app.auth

import okhttp3.Request

object ClaudeCliMimicryHeaders {

    val ALL: List<Pair<String, String>> = listOf(
        "User-Agent" to "claude-cli/2.1.280 (external, cli)",
        "X-Stainless-Lang" to "js",
        "X-Stainless-Package-Version" to "0.106.0",
        "X-Stainless-OS" to "Linux",
        "X-Stainless-Arch" to "arm64",
        "X-Stainless-Runtime" to "node",
        "X-Stainless-Runtime-Version" to "v24.18.0",
        "X-Stainless-Retry-Count" to "0",
        "X-Stainless-Timeout" to "600",
        "X-App" to "cli",
        "Anthropic-Dangerous-Direct-Browser-Access" to "true",
    )
}

fun Request.Builder.applyClaudeCliMimicryHeaders(): Request.Builder {
    for ((name, value) in ClaudeCliMimicryHeaders.ALL) {
        header(name, value)
    }
    return this
}
