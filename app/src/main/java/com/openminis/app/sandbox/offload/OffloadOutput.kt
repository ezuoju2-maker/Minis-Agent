package com.openminis.app.sandbox.offload

import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

internal object OffloadOutput {
    private const val TAG = "OffloadOutput"

    val OUTPUT_FLAGS: Set<String> = setOf("compact", "quiet", "q")

    fun formatBody(body: String, args: OffloadArgs): String {
        val compact = args.hasFlag("compact")
        val quiet = args.hasFlag("q", "quiet")
        if (!compact && !quiet) return body
        val trimmed = body.trimEnd('\n')

        val obj = parseObjectOrNull(trimmed)
        if (obj != null) {
            val payload: Any = if (quiet) extractEnvelopeField(obj) ?: obj else obj
            return serialize(payload, compact)
        }
        val arr = parseArrayOrNull(trimmed)
        if (arr != null) {
            return serialize(arr, compact)
        }
        return body
    }

    private fun extractEnvelopeField(obj: JSONObject): Any? {
        if (obj.has("error")) return obj.opt("error")
        if (obj.has("data")) return obj.opt("data")
        return null
    }

    private fun parseObjectOrNull(s: String): JSONObject? {
        val first = s.firstOrNull { !it.isWhitespace() } ?: return null
        if (first != '{') return null
        return try { JSONObject(s) } catch (_: Exception) { null }
    }

    private fun parseArrayOrNull(s: String): JSONArray? {
        val first = s.firstOrNull { !it.isWhitespace() } ?: return null
        if (first != '[') return null
        return try { JSONArray(s) } catch (_: Exception) { null }
    }

    private fun serialize(payload: Any, compact: Boolean): String = try {
        when (payload) {
            is JSONObject -> if (compact) payload.toString() else payload.toString(2)
            is JSONArray  -> if (compact) payload.toString() else payload.toString(2)
            is String     -> payload
            else          -> payload.toString()
        }
    } catch (e: Exception) {
        AppLogger.warning(TAG, "serialize failed: ${e.message}")
        payload.toString()
    }
}
