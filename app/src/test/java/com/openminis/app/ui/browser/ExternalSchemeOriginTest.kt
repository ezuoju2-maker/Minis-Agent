package com.openminis.app.ui.browser

import com.openminis.app.ui.browser.BrowserExternalSchemeHandler.Origin
import com.openminis.app.ui.browser.BrowserExternalSchemeHandler.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalSchemeOriginTest {

    private fun user(scheme: String?) =
        BrowserExternalSchemeHandler.route(scheme, Origin.USER_INITIATED)

    private fun agent(scheme: String?) =
        BrowserExternalSchemeHandler.route(scheme, Origin.AGENT_BACKGROUND)

    private val thirdPartyApps = listOf(
        "androidamap",
        "alipays",
        "taobao",
        "imeituan",
        "ctrip",
        "qqmusic",
        "orpheus",
        "dianping",
        "snssdk141",
        "weibo",
    )


    @Test
    fun `user tap dispatches every third-party app scheme`() {
        for (s in thirdPartyApps) {
            assertEquals(
                "user tap on $s:// must dispatch, not block",
                Route.VIEW_INTENT,
                user(s),
            )
        }
    }

    @Test
    fun `user tap dispatches a scheme nobody has ever heard of`() {
        assertEquals(Route.VIEW_INTENT, user("someapplaunchedtomorrow"))
    }


    @Test
    fun `agent background still blocks unknown app schemes`() {
        for (s in thirdPartyApps) {
            assertEquals(
                "agent-driven navigation to $s:// must stay blocked (T318)",
                Route.BLOCK,
                agent(s),
            )
        }
    }

    @Test
    fun `agent background blocks the original T318 offenders`() {
        for (s in listOf("toutiao", "snssdk141", "weibo")) {
            assertEquals(Route.BLOCK, agent(s))
        }
    }


    @Test
    fun `origin is the only thing that changes for an unknown scheme`() {
        assertEquals(Route.VIEW_INTENT, user("androidamap"))
        assertEquals(Route.BLOCK, agent("androidamap"))
    }


    @Test
    fun `allowlisted schemes dispatch in both modes`() {
        for (s in listOf("tel", "mailto", "sms", "geo", "market", "weixin")) {
            assertEquals("$s user", Route.VIEW_INTENT, user(s))
            assertEquals("$s agent", Route.VIEW_INTENT, agent(s))
        }
    }

    @Test
    fun `internal schemes load in the webview in both modes`() {
        for (s in listOf("http", "https", "about", "file")) {
            assertEquals("$s user", Route.LOAD_IN_WEBVIEW, user(s))
            assertEquals("$s agent", Route.LOAD_IN_WEBVIEW, agent(s))
        }
    }

    @Test
    fun `intent and android-app always take the parseUri path`() {
        for (s in listOf("intent", "android-app")) {
            assertEquals("$s user", Route.PARSE_URI, user(s))
            assertEquals("$s agent", Route.PARSE_URI, agent(s))
        }
    }


    @Test
    fun `scheme matching is case-insensitive`() {
        assertEquals(Route.LOAD_IN_WEBVIEW, user("HTTPS"))
        assertEquals(Route.PARSE_URI, user("Intent"))
        assertEquals(Route.VIEW_INTENT, agent("TEL"))
        assertEquals(Route.BLOCK, agent("AndroidAmap"))
    }

    @Test
    fun `a null scheme is treated as loadable, never dispatched`() {
        assertEquals(Route.LOAD_IN_WEBVIEW, user(null))
        assertEquals(Route.LOAD_IN_WEBVIEW, agent(null))
    }
}
