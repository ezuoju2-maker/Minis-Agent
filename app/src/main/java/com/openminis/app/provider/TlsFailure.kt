package com.openminis.app.provider

import com.openminis.app.data.model.LLMError

object TlsFailure {

    enum class Kind {
        UNTRUSTED,
        EXPIRED_OR_CLOCK,
        HOSTNAME_MISMATCH,
    }

    fun classify(error: Throwable): Kind? {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(12).toList()
        if (chain.none { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }) return null
        if (chain.any {
                it is java.security.cert.CertificateExpiredException ||
                    it is java.security.cert.CertificateNotYetValidException
            }
        ) return Kind.EXPIRED_OR_CLOCK
        if (chain.any { it is javax.net.ssl.SSLPeerUnverifiedException }) return Kind.HOSTNAME_MISMATCH
        if (chain.any {
                it is java.security.cert.CertPathValidatorException ||
                    it.message?.contains("Trust anchor for certification path not found") == true
            }
        ) return Kind.UNTRUSTED
        return null
    }

    fun toLLMError(error: Throwable, baseUrl: String): LLMError? {
        val kind = classify(error) ?: return null
        val host = hostOf(baseUrl)
        val root = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(12).last()
        com.openminis.app.logging.AppLogger.warning(
            "TLS",
            "[TLS] certificate rejected host=$host kind=$kind sdk=${android.os.Build.VERSION.SDK_INT} " +
                "top=${error.javaClass.simpleName}: ${error.message} root=${root.javaClass.simpleName}: ${root.message}",
        )
        return LLMError.CertificateError(host, kind, error)
    }

    fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl
}
