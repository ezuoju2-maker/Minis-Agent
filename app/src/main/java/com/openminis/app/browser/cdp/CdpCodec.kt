package com.openminis.app.browser.cdp

import org.json.JSONObject

object CdpCodec {

    fun encodeCall(id: Int, method: String, params: JSONObject? = null, sessionId: String? = null): String {
        val obj = JSONObject().put("id", id).put("method", method)
        if (params != null) obj.put("params", params)
        if (!sessionId.isNullOrEmpty()) obj.put("sessionId", sessionId)
        return obj.toString()
    }

    fun resultOf(response: String): JSONObject {
        val obj = JSONObject(response)
        obj.optJSONObject("error")?.let { err ->
            throw IllegalStateException(err.optString("message", "CDP error"))
        }
        return obj.optJSONObject("result") ?: JSONObject()
    }

    fun stringValue(result: JSONObject): String? {
        val inner = result.optJSONObject("result") ?: return null
        if (inner.has("value")) return inner.opt("value")?.toString()
        return inner.optString("description", null)
    }

    fun exceptionText(result: JSONObject): String? {
        val details = result.optJSONObject("exceptionDetails") ?: return null
        return details.optJSONObject("exception")?.optString("description")
            ?: details.optString("text", "evaluation failed")
    }

    fun clamp(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.take(max) + "\n… [truncated ${text.length - max} chars]"
    }
}
