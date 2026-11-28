package com.openminis.app.provider

object WireSanitize {

    fun token(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.replace("\r", "").replace("\n", "").trim()
    }

    fun baseUrl(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val withoutTrailingSlashes = trimmed.trimEnd('/')
        return if (withoutTrailingSlashes.contains("://")) withoutTrailingSlashes
        else "https://$withoutTrailingSlashes"
    }

    fun headerName(raw: String?): String =
        token(raw).replace(" ", "")

    fun queryEncoded(raw: String?): String =
        java.net.URLEncoder.encode(token(raw), "UTF-8")
}
