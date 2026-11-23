package com.openminis.app.data

object PayloadSizeAudit {

    const val SUSPICIOUS_BYTES_PER_TOKEN = 64

    const val MIN_BYTES_TO_AUDIT = 32 * 1024

    const val DANGEROUS_WINDOW_FRACTION = 0.5

    data class Finding(
        val bytes: Int,
        val estimatedTokens: Int,
        val bytesPerToken: Int,
        val suspicious: Boolean,
        val dangerous: Boolean,
    )

    fun audit(bytes: Int, estimatedTokens: Int, contextWindowTokens: Int): Finding {
        if (bytes < MIN_BYTES_TO_AUDIT) {
            return Finding(bytes, estimatedTokens, 0, suspicious = false, dangerous = false)
        }
        val safeTokens = estimatedTokens.coerceAtLeast(1)
        val ratio = bytes / safeTokens
        val suspicious = ratio >= SUSPICIOUS_BYTES_PER_TOKEN
        val dangerous = contextWindowTokens > 0 &&
            bytes.toLong() >= (contextWindowTokens * DANGEROUS_WINDOW_FRACTION).toLong()
        return Finding(bytes, estimatedTokens, ratio, suspicious, dangerous)
    }
}
