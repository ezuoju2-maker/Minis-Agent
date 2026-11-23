package com.openminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    class ProviderError(val detail: String, val httpStatus: Int? = null) :
        LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited : LLMError("Rate limited — please try again later")
    class TransientError(val detail: String, val httpStatus: Int? = null) :
        LLMError("Transient error: $detail")
    class CertificateError(
        val host: String,
        val kind: com.openminis.app.provider.TlsFailure.Kind,
        cause: Throwable,
    ) : LLMError(CertificateErrorText.describe(kind, host) + "\n(${cause.message})", cause)
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    val isNetworkError: Boolean get() = this is NetworkError

    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    val isFallbackable: Boolean get() =
        this is RateLimited || this is InvalidApiKey || this is ProviderError || this is CertificateError

    val httpServerErrorStatus: Int?
        get() = when (this) {
            is TransientError -> httpStatus?.takeIf { it in 500..599 }
            is ProviderError -> httpStatus?.takeIf { it in 500..599 }
                ?: LEADING_STATUS.find(detail)
                    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 500..599 }
            else -> null
        }

    val isHttpServerError: Boolean get() = httpServerErrorStatus != null

    private companion object {
        val LEADING_STATUS = Regex("""^\[(\d{3})\]""")
    }

    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is CertificateError -> "Certificate not trusted ($host)"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}

object CertificateErrorText {
    @Volatile var resolver: ((com.openminis.app.provider.TlsFailure.Kind, String) -> String)? = null

    fun describe(kind: com.openminis.app.provider.TlsFailure.Kind, host: String): String =
        runCatching { resolver?.invoke(kind, host) }.getOrNull() ?: english(kind, host)

    fun english(kind: com.openminis.app.provider.TlsFailure.Kind, host: String): String = when (kind) {
        com.openminis.app.provider.TlsFailure.Kind.UNTRUSTED ->
            "Couldn't verify the security certificate of $host (no trusted root). Common causes: a proxy, VPN " +
                "or packet-capture app re-signing HTTPS (official model services only trust system certificates), " +
                "a self-hosted or relay endpoint with a self-signed certificate or a missing intermediate " +
                "certificate, or a network that intercepts HTTPS. Turn off the capture tool or proxy and retry, " +
                "or check that endpoint's certificate setup."
        com.openminis.app.provider.TlsFailure.Kind.EXPIRED_OR_CLOCK ->
            "The certificate of $host is expired or not yet valid. Check that this phone's date and time are " +
                "correct (turn on automatic time); if they are, the service's certificate has expired."
        com.openminis.app.provider.TlsFailure.Kind.HOSTNAME_MISMATCH ->
            "$host answered with a certificate for a different name, so the connection is probably being " +
                "intercepted (a public Wi-Fi sign-in page, the carrier, or a proxy). Switch networks or turn off " +
                "the proxy and retry."
    }
}
