package com.openminis.app.mcp.oauth

import org.json.JSONObject

data class MCPOAuthConfig(
    val mode: String = MODE_STATIC,
    val clientId: String = "",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val scopes: String? = null,
    val redirectUri: String? = null,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() &&
            authorizationEndpoint.isNotBlank() &&
            tokenEndpoint.isNotBlank()

    val isDynamic: Boolean get() = mode == MODE_DYNAMIC

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("clientId", clientId)
        put("authorizationEndpoint", authorizationEndpoint)
        put("tokenEndpoint", tokenEndpoint)
        scopes?.takeIf { it.isNotBlank() }?.let { put("scopes", it) }
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirectURI", it) }
        put("client_id", clientId)
        put("authorization_endpoint", authorizationEndpoint)
        put("token_endpoint", tokenEndpoint)
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirect_uri", it) }
    }

    companion object {
        const val MODE_STATIC = "static"
        const val MODE_DYNAMIC = "dynamic"

        fun fromJson(obj: JSONObject?): MCPOAuthConfig? {
            if (obj == null) return null
            fun str(camel: String, snake: String): String =
                obj.optString(camel, "").ifBlank { obj.optString(snake, "") }
            val config = MCPOAuthConfig(
                mode = obj.optString("mode", MODE_STATIC).ifBlank { MODE_STATIC },
                clientId = str("clientId", "client_id"),
                authorizationEndpoint = str("authorizationEndpoint", "authorization_endpoint"),
                tokenEndpoint = str("tokenEndpoint", "token_endpoint"),
                scopes = obj.optString("scopes", "").ifBlank { null },
                redirectUri = str("redirectURI", "redirect_uri").ifBlank { null },
            )
            if (config.isDynamic) return config
            return if (config.clientId.isBlank() &&
                config.authorizationEndpoint.isBlank() &&
                config.tokenEndpoint.isBlank()
            ) {
                null
            } else {
                config
            }
        }
    }
}
