package com.openminis.app.mcp.oauth

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object McpPkce {

    private const val URL_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private val random = SecureRandom()

    fun randomUrlSafe(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(URL_SAFE[random.nextInt(URL_SAFE.length)]) }
        return sb.toString()
    }

    fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    data class PkceParams(val verifier: String, val challenge: String, val state: String)

    fun newPkce(): PkceParams {
        val verifier = randomUrlSafe(64)
        return PkceParams(
            verifier = verifier,
            challenge = codeChallengeS256(verifier),
            state = randomUrlSafe(24),
        )
    }

    fun canonicalResourceUri(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash)
        while (s.endsWith("/")) s = s.dropLast(1)
        if (s.isEmpty()) return null
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) return null
        return s
    }

    fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        pkce: PkceParams,
        resource: String?,
        scopes: String?,
    ): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to clientId)
            add("redirect_uri" to redirectUri)
            add("state" to pkce.state)
            add("code_challenge" to pkce.challenge)
            add("code_challenge_method" to "S256")
            add("access_type" to "offline")
            add("prompt" to "consent")
            if (!resource.isNullOrEmpty()) add("resource" to resource)
            if (!scopes.isNullOrBlank()) add("scope" to scopes.trim())
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val sep = if (authorizationEndpoint.contains('?')) "&" else "?"
        return "$authorizationEndpoint$sep$query"
    }

    private fun enc(v: String): String =
        URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}
