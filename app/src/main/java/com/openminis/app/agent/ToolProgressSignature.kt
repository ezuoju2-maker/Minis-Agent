package com.openminis.app.agent

import java.security.MessageDigest

object ToolProgressSignature {

    private class Rule(val regex: Regex, val token: String)

    private val RULES: List<Rule> = listOf(
        Rule(Regex("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\b"), "<ts>"),
        Rule(Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"), "<uuid>"),
        Rule(Regex("\\b0x[0-9a-fA-F]{6,16}\\b"), "<addr>"),
        Rule(Regex("(?i)\\b(pid|tid|thread|process)\\s*[=:]\\s*\\d+\\b"), "<id>"),
        Rule(Regex("\\b\\d+(?:\\.\\d+)?(?:ms|us|ns|s)\\b"), "<dur>"),
    )

    private const val HEX = "0123456789abcdef"

    fun normalize(text: String): String {
        var out = text
        for (rule in RULES) out = rule.regex.replace(out, rule.token)
        return out.trim()
    }

    fun signature(result: String?, error: String?): String {
        val payload = "err=" + normalize(error ?: "") + "\nout=" + normalize(result ?: "")
        return sha256(payload)
    }

    fun hasProgress(previous: String?, current: String?): Boolean =
        signature(previous, null) != signature(current, null)

    fun canonicalNumber(value: Number): String = when (value) {
        is Double -> if (!value.isNaN() && !value.isInfinite() && value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Float -> if (!value.isNaN() && !value.isInfinite() && value == value.toLong().toFloat()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        else -> value.toString()
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
