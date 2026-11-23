package com.openminis.app.mcp.oauth

import org.json.JSONObject

object MCPOAuthSignInLogic {


    data class ClientRecord(
        val clientId: String,
        val redirectUri: String,
        val registeredAt: Double,
        val secretExpiresAt: Double? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("clientId", clientId)
            put("redirectURI", redirectUri)
            put("registeredAt", registeredAt)
            secretExpiresAt?.let { put("secretExpiresAt", it) }
        }

        companion object {
            fun fromJson(obj: Any?): ClientRecord? {
                val o = obj as? JSONObject ?: return null
                val id = o.optString("clientId", "")
                if (id.isEmpty() || !o.has("redirectURI")) return null
                return ClientRecord(
                    clientId = id,
                    redirectUri = o.optString("redirectURI", ""),
                    registeredAt = o.optDouble("registeredAt", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    secretExpiresAt = if (o.has("secretExpiresAt")) o.optDouble("secretExpiresAt").takeIf { !it.isNaN() } else null,
                )
            }
        }
    }

    fun record(issuer: String, v2: JSONObject?, v1: JSONObject?, defaultRedirectUri: String): ClientRecord? {
        ClientRecord.fromJson(v2?.opt(issuer))?.let { return it }
        val id = v1?.optString(issuer, "").orEmpty()
        if (id.isNotEmpty()) return ClientRecord(id, defaultRedirectUri, 0.0)
        return null
    }

    sealed class Reuse {
        object Cached : Reuse()
        data class RegisterAgain(val reason: String) : Reuse()
    }

    fun reuse(record: ClientRecord?, redirectUri: String, now: Double): Reuse {
        if (record == null) return Reuse.RegisterAgain("no registration")
        if (record.redirectUri != redirectUri) return Reuse.RegisterAgain("registered for another redirect URI")
        val expires = record.secretExpiresAt
        if (expires != null && expires > 0 && expires <= now) return Reuse.RegisterAgain("client secret expired")
        return Reuse.Cached
    }

    fun issuers(withClientId: String, v2: JSONObject?, v1: JSONObject?): List<String> {
        if (withClientId.isEmpty()) return emptyList()
        val out = sortedSetOf<String>()
        v2?.keys()?.forEach { k -> if (ClientRecord.fromJson(v2.opt(k))?.clientId == withClientId) out.add(k) }
        v1?.keys()?.forEach { k -> if (v1.optString(k, "") == withClientId) out.add(k) }
        return out.toList()
    }


    sealed class TokenFailure {
        object ClientRejected : TokenFailure()

        object GrantRejected : TokenFailure()
        data class Other(val detail: String) : TokenFailure()
    }

    fun tokenFailure(body: String, status: Int): TokenFailure {
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val code = obj?.optString("error", "").orEmpty()
        val detail = obj?.optString("error_description", "").orEmpty()
        return when (code) {
            "invalid_client", "unauthorized_client" -> TokenFailure.ClientRejected
            "invalid_grant" -> TokenFailure.GrantRejected
            else -> when {
                detail.isNotEmpty() -> TokenFailure.Other(detail)
                code.isNotEmpty() -> TokenFailure.Other(code)
                else -> {
                    val text = body.take(200)
                    TokenFailure.Other(text.ifEmpty { "HTTP $status" })
                }
            }
        }
    }

    fun registersAgain(afterClientRejectedOnAttempt: Int, dynamic: Boolean): Boolean =
        dynamic && afterClientRejectedOnAttempt == 0

    fun issuerMatches(expected: String?, received: String?): Boolean {
        if (received.isNullOrEmpty() || expected.isNullOrEmpty()) return true
        return expected.trimEnd('/') == received.trimEnd('/')
    }


    enum class AuthKind { NONE, BROWSER, MANUAL }

    fun authKind(oauth: MCPOAuthConfig?): AuthKind = when {
        oauth == null -> AuthKind.NONE
        oauth.isDynamic -> AuthKind.BROWSER
        else -> AuthKind.MANUAL
    }

    fun browserBlock(discovered: MCPOAuthConfig?): MCPOAuthConfig =
        discovered?.copy(mode = MCPOAuthConfig.MODE_DYNAMIC) ?: MCPOAuthConfig(mode = MCPOAuthConfig.MODE_DYNAMIC)

    fun canSignIn(name: String, url: String, busy: Boolean): Boolean =
        !busy && name.isNotBlank() && url.isNotBlank()
}
