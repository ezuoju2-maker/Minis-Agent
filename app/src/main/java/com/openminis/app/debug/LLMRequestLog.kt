package com.openminis.app.debug

import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

object LLMRequestLog {

    private const val MAX_ENTRIES = 20

    private const val MAX_BODY_CHARS = 64 * 1024

    data class Entry(
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
        val requestURL: String,
        val requestMethod: String = "POST",
        val requestHeaders: Map<String, String>,
        val requestBody: String,
        val durationMs: Long = 0,
        val responseStatusCode: Int = 0,
        val responseBody: String = "",
        val usage: JSONObject? = null,
    )

    private val entries = mutableListOf<Entry>()

    private var lastFullBody: String? = null
    private var lastFullTimestamp: Long = 0

    @Synchronized
    fun add(entry: Entry) {
        if (!BuildConfig.DEV_TOOLS) return
        val redacted = entry.copy(
            requestHeaders = redactHeaders(entry.requestHeaders),
            requestURL = redactUrl(entry.requestURL),
        )
        lastFullBody = redacted.requestBody
        lastFullTimestamp = redacted.timestamp
        val safeEntry = if (redacted.requestBody.length > MAX_BODY_CHARS) {
            redacted.copy(
                requestBody = redacted.requestBody.take(MAX_BODY_CHARS) +
                    "\n…[truncated ${redacted.requestBody.length - MAX_BODY_CHARS} chars]",
            )
        } else redacted
        entries.add(safeEntry)
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    private val SECRET_HEADERS = setOf(
        "authorization", "proxy-authorization", "x-api-key", "api-key",
        "x-goog-api-key", "cookie", "set-cookie",
    )

    private val SECRET_QUERY_PARAMS = setOf("key", "api_key", "apikey", "access_token", "token")

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            val lower = name.lowercase()
            val trimmed = value.trim()
            when {
                lower !in SECRET_HEADERS -> value
                (lower == "authorization" || lower == "proxy-authorization") && ' ' in trimmed ->
                    "${trimmed.substringBefore(' ')} ***"
                else -> "***"
            }
        }

    fun redactUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val fragmentAt = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val query = url.substring(q + 1, fragmentAt).split('&').joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if ('=' in pair && name.lowercase() in SECRET_QUERY_PARAMS) "$name=***" else pair
        }
        return url.substring(0, q + 1) + query + url.substring(fragmentAt)
    }

    @Synchronized
    fun getAll(): List<Entry> = entries.toList()

    @Synchronized
    fun getLast(n: Int): List<Entry> = entries.takeLast(n)

    @Synchronized
    fun clear() {
        entries.clear()
        lastFullBody = null
    }

    @Synchronized
    fun lastFullJSON(): JSONObject? {
        val body = lastFullBody ?: return null
        return JSONObject().apply {
            put("timestamp", lastFullTimestamp)
            put("length", body.length)
            put("requestBody", body)
        }
    }

    fun toJSON(last: Int? = null): JSONObject {
        val list = if (last != null) getLast(last) else getAll()
        val array = JSONArray()
        for (entry in list) {
            array.put(JSONObject().apply {
                put("provider", entry.provider)
                put("timestamp", entry.timestamp)
                put("requestURL", entry.requestURL)
                put("requestMethod", entry.requestMethod)
                put("requestHeaders", JSONObject(entry.requestHeaders))
                put("requestBody", try { JSONObject(entry.requestBody) } catch (_: Exception) { entry.requestBody })
                put("durationMs", entry.durationMs)
                put("responseStatusCode", entry.responseStatusCode)
                if (entry.responseBody.isNotEmpty()) {
                    put("responseBody", entry.responseBody)
                }
                if (entry.usage != null) {
                    put("usage", entry.usage)
                }
            })
        }
        return JSONObject().apply {
            put("count", list.size)
            put("requests", array)
        }
    }
}
