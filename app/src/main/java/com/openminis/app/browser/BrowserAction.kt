package com.openminis.app.browser

enum class BrowserAction(val value: String) {
    NAVIGATE("navigate"),
    SCREENSHOT("screenshot"),
    CLICK("click"),
    TYPE("type"),
    GET_TEXT("get_text"),
    SCROLL("scroll"),
    GET_PAGE_INFO("get_page_info"),
    EXECUTE_JS("execute_js"),
    FIND_ELEMENTS("find_elements"),
    HOVER("hover"),
    GET_READABLE("get_readable"),
    SET_USER_AGENT("set_user_agent"),
    SET_VIEWPORT("set_viewport"),
    GET_BACKBONE("get_backbone"),
    FETCH("fetch"),
    NEW_TAB("new_tab"),
    CLOSE_TAB("close_tab"),
    LIST_TABS("list_tabs"),
    GET_COOKIES("get_cookies"),
    SET_COOKIES("set_cookies"),
    SCROLL_AND_COLLECT("scroll_and_collect"),
    WAIT_FOR_DOM_STABLE("wait_for_dom_stable");

    val opensNewPage: Boolean get() = this in opensNewPageActions

    companion object {
        private val map = entries.associateBy { it.value }
        fun fromString(s: String): BrowserAction? = map[s]

        val visualChangeActions: Set<BrowserAction> = setOf(
            NAVIGATE, CLICK, SCROLL, HOVER, TYPE
        )

        val opensNewPageActions: Set<BrowserAction> = setOf(
            NAVIGATE
        )

        val allValues: List<String> = entries.map { it.value }
    }
}

enum class ScrollDirection(val value: String) {
    UP("up"), DOWN("down");
    companion object {
        fun fromString(s: String): ScrollDirection? = entries.find { it.value == s }
    }
}

enum class UserAgentProfile(val value: String) {
    MOBILE_CHROME("mobile_chrome"),
    DESKTOP_CHROME("desktop_chrome"),
    CUSTOM("custom");

    val userAgentString: String?
        get() = when (this) {
            MOBILE_CHROME -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            DESKTOP_CHROME -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            CUSTOM -> null
        }

    val displayName: String
        get() = when (this) {
            MOBILE_CHROME -> "Mobile Chrome"
            DESKTOP_CHROME -> "Desktop Chrome"
            CUSTOM -> "Custom"
        }

    val viewportSize: Pair<Int, Int>
        get() = when (this) {
            DESKTOP_CHROME -> 1280 to 800
            MOBILE_CHROME, CUSTOM -> 412 to 915
        }

    companion object {
        fun fromString(s: String): UserAgentProfile? = entries.find { it.value == s }

        fun parse(raw: String?): UserAgentProfile? {
            val s = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
            return when (s) {
                "desktop" -> DESKTOP_CHROME
                "mobile" -> MOBILE_CHROME
                else -> fromString(s)
            }
        }
    }
}
