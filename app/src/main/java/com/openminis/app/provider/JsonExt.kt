package com.openminis.app.provider

import org.json.JSONObject

fun JSONObject.safeOptString(key: String, fallback: String = ""): String {
    if (!has(key) || isNull(key)) return fallback
    return optString(key, fallback)
}
