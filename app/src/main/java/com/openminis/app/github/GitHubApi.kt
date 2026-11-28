package com.openminis.app.github

import com.openminis.app.network.NetworkHttp
import com.openminis.app.network.NetworkUrlRequest

class GitHubApi(private val token: String?, private val timeoutMs: Int = 30_000) {

    data class Reply(
        val code: Int,
        val body: String,
        val rateRemaining: String,
        val rateLimit: String,
        val scopes: String,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && code in 200..299
    }

    fun request(method: String, path: String, body: String? = null): Reply {
        val normalized = normalizePath(path)
            ?: return Reply(0, "", "", "", "", error = "Invalid path: give an API path like /repos/owner/name.")
        val verb = method.trim().uppercase()
        if (verb !in METHODS) {
            return Reply(0, "", "", "", "", error = "Unsupported method $verb.")
        }
        val payload = body?.trim()?.takeIf { it.isNotEmpty() }
        if (payload != null && payload.length > MAX_REQUEST_CHARS) {
            return Reply(0, "", "", "", "", error = "Request body too large (${payload.length} chars, max $MAX_REQUEST_CHARS).")
        }
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            put("User-Agent", "Minis-Agent/2.0 github-connector")
            if (payload != null) put("Content-Type", "application/json")
            token?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return try {
            NetworkUrlRequest.open(
                NetworkHttp.DEFAULT_TAG,
                "https://api.github.com$normalized",
                method = verb,
                headers = headers,
                body = payload?.toByteArray(),
                connectTimeoutMs = 10_000,
                readTimeoutMs = timeoutMs,
            ).use { r ->
                val text = try {
                    r.text()
                } catch (e: Exception) {
                    if (r.code >= 400) "" else (e.message ?: "")
                }
                fun h(name: String): String =
                    r.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
                Reply(
                    code = r.code,
                    body = text.take(MAX_RESPONSE_CHARS),
                    rateRemaining = h("x-ratelimit-remaining"),
                    rateLimit = h("x-ratelimit-limit"),
                    scopes = h("x-oauth-scopes"),
                )
            }
        } catch (e: Exception) {
            Reply(0, "", "", "", "", error = "Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        val METHODS = setOf("GET", "POST", "PATCH", "PUT", "DELETE", "HEAD")
        const val MAX_REQUEST_CHARS = 100_000
        const val MAX_RESPONSE_CHARS = 6_000

        fun normalizePath(raw: String): String? {
            val path = raw.trim()
            if (path.isEmpty()) return null
            if (path.contains("://") || path.startsWith("http", true)) return null
            if (path.any { it == '\n' || it == '\r' || it == ' ' }) return null
            return if (path.startsWith("/")) path else "/$path"
        }

        fun invalidBody(body: String): String? = try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) org.json.JSONArray(trimmed) else org.json.JSONObject(trimmed)
            null
        } catch (e: Exception) {
            "body is not valid JSON (${e.javaClass.simpleName})"
        }

        fun isRepoDelete(path: String, method: String): Boolean =
            method.trim().uppercase() == "DELETE" && Regex("^/repos/[^/]+/[^/]+/?$").matches(path.trim())

        fun summarize(reply: Reply): String = buildString {
            if (reply.error != null) {
                append("Error: ").append(reply.error)
                return@buildString
            }
            append("HTTP ").append(reply.code)
            if (reply.rateRemaining.isNotEmpty() && reply.rateLimit.isNotEmpty()) {
                append(" · rate ").append(reply.rateRemaining).append('/').append(reply.rateLimit)
            }
            if (reply.scopes.isNotEmpty()) append(" · scopes ").append(reply.scopes)
            if (reply.body.isNotEmpty()) append('\n').append(reply.body)
        }
    }
}
