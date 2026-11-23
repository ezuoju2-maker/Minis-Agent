package com.openminis.app.provider

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object OpenCodeSessionHeader {

    const val HEADER = "x-opencode-session"

    private const val HOST = "opencode.ai"

    fun matches(baseUrl: String?): Boolean {
        val raw = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val normalized = if (raw.contains("://")) raw else "https://$raw"
        val host = normalized.toHttpUrlOrNull()?.host ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }

    fun headersFor(baseUrl: String?, sessionId: String?): Map<String, String> {
        if (!matches(baseUrl)) return emptyMap()
        val id = sessionId?.trim().orEmpty()
        if (!isPersistedSessionId(id)) return emptyMap()
        return mapOf(HEADER to id)
    }

    fun isPersistedSessionId(sessionId: String?): Boolean {
        val id = sessionId?.trim().orEmpty()
        return id.isNotEmpty() && !id.startsWith("__")
    }
}
