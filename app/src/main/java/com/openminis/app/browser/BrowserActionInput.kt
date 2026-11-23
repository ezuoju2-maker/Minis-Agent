package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject

data class BrowserActionInput(
    val action: BrowserAction,
    val url: String? = null,
    val selector: String? = null,
    val text: String? = null,
    val coordinateX: Int? = null,
    val coordinateY: Int? = null,
    val direction: ScrollDirection? = null,
    val amount: Int? = null,
    val script: String? = null,
    val userAgent: UserAgentProfile? = null,
    val userAgentRaw: String? = null,
    val maxDepth: Int? = null,
    val tabId: Int? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val reset: Boolean = false,
    val keywords: List<String>? = null,
    val fuzzy: Boolean = false,
    val itemSelector: String? = null,
    val scrollCount: Int? = null,
    val timeoutMs: Int? = null,
    val fullPage: Boolean = false,
    val cookies: List<Map<String, Any?>>? = null,
) {
    companion object {
        fun parse(json: String): BrowserActionInput? {
            return try {
                val obj = JSONObject(json)
                val actionStr = obj.optString("action", "")
                val action = BrowserAction.fromString(actionStr) ?: return null

                BrowserActionInput(
                    action = action,
                    url = obj.optString("url").ifEmpty { null },
                    selector = obj.optString("selector").ifEmpty { null },
                    text = obj.optString("text").ifEmpty { null },
                    coordinateX = if (obj.has("coordinate_x")) obj.optInt("coordinate_x") else null,
                    coordinateY = if (obj.has("coordinate_y")) obj.optInt("coordinate_y") else null,
                    direction = obj.optString("direction").ifEmpty { null }?.let { ScrollDirection.fromString(it) },
                    amount = if (obj.has("amount")) obj.optInt("amount") else null,
                    script = obj.optString("script").ifEmpty { null },
                    userAgent = UserAgentProfile.parse(obj.optString("user_agent").ifEmpty { null }),
                    userAgentRaw = obj.optString("user_agent").ifEmpty { null },
                    maxDepth = if (obj.has("max_depth")) obj.optInt("max_depth") else null,
                    tabId = if (obj.has("tab_id")) obj.optInt("tab_id") else null,
                    viewportWidth = if (obj.has("viewport_width")) obj.optInt("viewport_width") else null,
                    viewportHeight = if (obj.has("viewport_height")) obj.optInt("viewport_height") else null,
                    reset = obj.optBoolean("reset", false),
                    keywords = obj.optJSONArray("keywords")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                            .takeIf { it.isNotEmpty() }
                    },
                    fuzzy = obj.optBoolean("fuzzy", false),
                    itemSelector = obj.optString("item_selector").ifEmpty { null },
                    scrollCount = if (obj.has("scroll_count")) obj.optInt("scroll_count") else null,
                    timeoutMs = if (obj.has("timeout")) obj.optInt("timeout") else null,
                    fullPage = obj.optBoolean("full_page", false),
                    cookies = parseCookies(obj),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseCookies(obj: JSONObject): List<Map<String, Any?>>? {
            val arr: JSONArray? = obj.optJSONArray("cookies")
                ?: obj.optString("cookies").takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
            arr ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { co ->
                    co.keys().asSequence().associateWith { k -> co.get(k) }
                }
            }.takeIf { it.isNotEmpty() }
        }
    }
}
