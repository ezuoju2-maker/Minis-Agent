package com.openminis.app.auth

object OAuthLogRedaction {

    private val SECRET_PARAMS = listOf(
        "code", "state", "code_verifier", "code_challenge",
        "access_token", "refresh_token", "id_token", "token",
        "key", "api_key", "client_secret", "user_code", "device_code",
    )

    private val PARAM_REGEX = Regex(
        "(?<=[?&#])(" + SECRET_PARAMS.joinToString("|") { Regex.escape(it) } + ")=([^&#\\s]*)",
    )

    private const val MIN_LEN_FOR_PREFIX = 8

    fun secret(value: String?): String {
        if (value == null) return "<null>"
        if (value.length < MIN_LEN_FOR_PREFIX) return "<len=${value.length}>"
        return "<len=${value.length} prefix=${value.take(4)}…>"
    }

    private val SECRET_JSON = Regex(
        "(\"(?:access_token|refresh_token|id_token|token|code|code_verifier|state|client_secret|api_key|key)\"\\s*:\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"",
    )

    fun jsonBody(body: String?): String {
        if (body == null) return "<null>"
        return SECRET_JSON.replace(body) { m -> "${m.groupValues[1]}\"<len=${m.groupValues[2].length}>\"" }
    }

    fun url(url: String?): String {
        if (url == null) return "<null>"
        return PARAM_REGEX.replace(url) { m -> "${m.groupValues[1]}=${secret(m.groupValues[2])}" }
    }
}
