package com.openminis.app.skills.remote

sealed class SourceVerdict {
    object Allowed : SourceVerdict()
    data class Rejected(val reason: String) : SourceVerdict()
}

class RemoteSourcePolicy(
    private val allowedHosts: Set<String> = DEFAULT_HOSTS,
    private val allowHttp: Boolean = false,
) {

    fun check(raw: String): SourceVerdict {
        val url = raw.trim()
        if (url.isEmpty()) return SourceVerdict.Rejected("empty url")
        if (url.length > MAX_URL) return SourceVerdict.Rejected("url too long")
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme == "http" && !allowHttp) return SourceVerdict.Rejected("plain http is not allowed")
        if (scheme != "https" && scheme != "http") return SourceVerdict.Rejected("unsupported scheme: $scheme")
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.contains('@')) return SourceVerdict.Rejected("credentials in url are not allowed")
        val hostPart = authority.substringBefore(':').lowercase()
        val port = authority.substringAfter(':', "").toIntOrNull()
        if (port != null && port != 443 && port != 80) return SourceVerdict.Rejected("unexpected port: $port")
        if (hostPart.isEmpty()) return SourceVerdict.Rejected("missing host")
        if (hostPart == "localhost" || hostPart.endsWith(".localhost")) {
            return SourceVerdict.Rejected("localhost is not allowed")
        }
        if (isPrivateLiteral(hostPart)) return SourceVerdict.Rejected("private address is not allowed")
        val covered = allowedHosts.any { allowed -> hostPart == allowed || hostPart.endsWith(".$allowed") }
        return if (covered) SourceVerdict.Allowed else SourceVerdict.Rejected("untrusted host: $hostPart")
    }

    fun isAllowed(raw: String): Boolean = check(raw) is SourceVerdict.Allowed

    private fun isPrivateLiteral(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        if (a == 127) return true
        if (a == 10 || a == 0) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
        if (a == 169 && b == 254) return true
        return false
    }

    companion object {
        const val MAX_URL = 2048

        val DEFAULT_HOSTS: Set<String> = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "codeload.github.com",
            "api.github.com",
        )
    }
}
