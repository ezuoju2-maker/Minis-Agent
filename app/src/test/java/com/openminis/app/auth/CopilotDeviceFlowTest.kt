package com.openminis.app.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotDeviceFlowTest {


    @Test
    fun `http 200 with authorization_pending is pending, not success`() {
        val json = JSONObject("""{"error":"authorization_pending"}""")
        assertEquals(
            CopilotDeviceFlow.PollResult.Pending,
            CopilotDeviceFlow.classifyPoll(json, httpOK = true),
        )
    }

    @Test
    fun `an access token is success`() {
        val json = JSONObject("""{"access_token":"gho_abc123"}""")
        val r = CopilotDeviceFlow.classifyPoll(json, httpOK = true)
        assertTrue(r is CopilotDeviceFlow.PollResult.Success)
        assertEquals("gho_abc123", (r as CopilotDeviceFlow.PollResult.Success).accessToken)
    }

    @Test
    fun `terminal errors are classified apart from retryable ones`() {
        fun cls(err: String) = CopilotDeviceFlow.classifyPoll(
            JSONObject("""{"error":"$err"}"""), httpOK = false,
        )
        assertEquals(CopilotDeviceFlow.PollResult.SlowDown, cls("slow_down"))
        assertTrue(cls("access_denied") is CopilotDeviceFlow.PollResult.Denied)
        assertTrue(cls("expired_token") is CopilotDeviceFlow.PollResult.Expired)
        assertTrue(cls("unauthorized_client") is CopilotDeviceFlow.PollResult.Fatal)
    }

    @Test
    fun `slow_down backs off by five seconds per RFC 8628`() {
        assertEquals(10L, CopilotDeviceFlow.bumpedInterval(5))
        assertEquals(15L, CopilotDeviceFlow.bumpedInterval(10))
    }


    @Test
    fun `device authorization parses and defaults interval and expiry`() {
        val a = CopilotDeviceFlow.parseDeviceAuthorization(
            JSONObject("""{"device_code":"d","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device"}"""),
        )!!
        assertEquals("ABCD-1234", a.userCode)
        assertEquals(5L, a.intervalSeconds)
        assertEquals(900L, a.expiresInSeconds)
    }

    @Test
    fun `device authorization missing a required field is null`() {
        assertNull(
            CopilotDeviceFlow.parseDeviceAuthorization(
                JSONObject("""{"user_code":"ABCD","verification_uri":"https://x"}"""),
            ),
        )
    }


    @Test
    fun `session token prefers absolute expires_at`() {
        val s = CopilotDeviceFlow.parseSessionToken(
            JSONObject("""{"token":"tid=x","expires_at":2000,"refresh_in":1500}"""),
            nowEpochSeconds = 1000,
        )!!
        assertEquals(2000L, s.expiresAtEpochSeconds)
    }

    @Test
    fun `session token falls back to refresh_in`() {
        val s = CopilotDeviceFlow.parseSessionToken(
            JSONObject("""{"token":"tid=x","refresh_in":1500}"""),
            nowEpochSeconds = 1000,
        )!!
        assertEquals(2500L, s.expiresAtEpochSeconds)
    }

    @Test
    fun `session token with no expiry at all is rejected`() {
        assertNull(
            CopilotDeviceFlow.parseSessionToken(JSONObject("""{"token":"tid=x"}"""), 1000),
        )
    }

    @Test
    fun `session token refreshes sixty seconds before expiry`() {
        val s = CopilotDeviceFlow.SessionToken("t", expiresAtEpochSeconds = 1000)
        assertFalse(s.needsRefresh(930))
        assertTrue(s.needsRefresh(940))
        assertTrue(s.needsRefresh(1001))
    }


    @Test
    fun `an explicit picker-disabled entry is dropped`() {
        val json = JSONObject(
            """{"data":[
                 {"id":"gpt-4o","name":"GPT-4o","model_picker_enabled":true},
                 {"id":"text-embedding-3","name":"Embedding","model_picker_enabled":false},
                 {"id":"internal-x","name":"Internal"}
               ]}"""
        )
        val ids = CopilotDeviceFlow.parseModelIds(json)
        assertEquals(
            listOf("gpt-4o" to "GPT-4o", "internal-x" to "Internal"),
            ids,
        )
    }

    @Test
    fun `model without a name falls back to its id`() {
        val json = JSONObject("""{"data":[{"id":"o4-mini","model_picker_enabled":true}]}""")
        assertEquals(listOf("o4-mini" to "o4-mini"), CopilotDeviceFlow.parseModelIds(json))
    }


    @Test
    fun `token exchange uses the token scheme, not bearer`() {
        val h = CopilotDeviceFlow.tokenExchangeHeaders("gho_x")
        assertEquals("token gho_x", h["Authorization"])
    }

    @Test
    fun `chat headers mark initiator and add vision only when needed`() {
        val user = CopilotDeviceFlow.chatHeaders(false, hasImages = false, requestId = "r1")
        assertEquals("user", user["X-Initiator"])
        assertFalse(user.containsKey("Copilot-Vision-Request"))

        val agent = CopilotDeviceFlow.chatHeaders(true, hasImages = true, requestId = "r2")
        assertEquals("agent", agent["X-Initiator"])
        assertEquals("true", agent["Copilot-Vision-Request"])
        assertEquals("r2", agent["X-Request-Id"])
    }


    private fun msg(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("content", text)

    private fun imageMsg(role: String, type: String): JSONObject {
        val parts = org.json.JSONArray().put(JSONObject().put("type", type))
        return JSONObject().put("role", role).put("content", parts)
    }

    @Test
    fun `a user turn is labelled user`() {
        val body = JSONObject().put("messages", org.json.JSONArray().put(msg("user", "hi")))
        val h = CopilotDeviceFlow.perRequestHeaders(body)
        assertEquals("user", h["X-Initiator"])
        assertNull(h["Copilot-Vision-Request"])
    }

    @Test
    fun `a trailing tool result is labelled agent`() {
        val msgs = org.json.JSONArray()
            .put(msg("user", "hi"))
            .put(msg("assistant", "calling a tool"))
            .put(msg("tool", "result"))
        val h = CopilotDeviceFlow.perRequestHeaders(JSONObject().put("messages", msgs))
        assertEquals("agent", h["X-Initiator"])
    }

    @Test
    fun `a tool result earlier in the history does not make the turn agent`() {
        val msgs = org.json.JSONArray()
            .put(msg("tool", "result"))
            .put(msg("user", "thanks, now do this"))
        val h = CopilotDeviceFlow.perRequestHeaders(JSONObject().put("messages", msgs))
        assertEquals("user", h["X-Initiator"])
    }

    @Test
    fun `the vision flag is set only when an image is attached`() {
        val chat = JSONObject().put(
            "messages", org.json.JSONArray().put(imageMsg("user", "image_url")),
        )
        assertEquals("true", CopilotDeviceFlow.perRequestHeaders(chat)["Copilot-Vision-Request"])

        val responses = JSONObject().put(
            "input", org.json.JSONArray().put(imageMsg("user", "input_image")),
        )
        assertEquals("true", CopilotDeviceFlow.perRequestHeaders(responses)["Copilot-Vision-Request"])
    }

    @Test
    fun `the request id differs per call`() {
        val body = JSONObject().put("messages", org.json.JSONArray().put(msg("user", "hi")))
        val a = CopilotDeviceFlow.perRequestHeaders(body)["X-Request-Id"]
        val b = CopilotDeviceFlow.perRequestHeaders(body)["X-Request-Id"]
        assertTrue(!a.isNullOrEmpty())
        assertTrue("two calls must not share a request id", a != b)
    }

    @Test
    fun `an empty or unrecognised body is still answered safely`() {
        val h = CopilotDeviceFlow.perRequestHeaders(JSONObject())
        assertEquals("user", h["X-Initiator"])
        assertNull(h["Copilot-Vision-Request"])
    }

    @Test
    fun `static headers carry the editor identity and nothing per-request`() {
        val h = CopilotDeviceFlow.staticChatHeaders()
        assertEquals(CopilotDeviceFlow.EDITOR_VERSION, h["Editor-Version"])
        assertEquals(CopilotDeviceFlow.INTEGRATION_ID, h["Copilot-Integration-Id"])
        assertNull(h["X-Initiator"])
        assertNull(h["X-Request-Id"])
        assertNull(h["Copilot-Vision-Request"])
    }


    @Test
    fun `capabilities are carried through, not discarded`() {
        val json = JSONObject(
            """
            {"data":[{"id":"gpt-5","name":"GPT-5","model_picker_enabled":true,
              "capabilities":{"type":"chat",
                "limits":{"max_context_window_tokens":128000,"max_output_tokens":16384},
                "supports":{"vision":true,"thinking":true}}}]}
            """.trimIndent(),
        )
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertEquals("gpt-5", m.id)
        assertEquals("GPT-5", m.displayName)
        assertEquals(128000, m.contextWindow)
        assertEquals(16384, m.maxOutputTokens)
        assertTrue(m.supportsVision)
        assertEquals(true, m.supportsReasoning)
    }

    @Test
    fun `a missing picker flag means visible, matching iOS`() {
        val json = JSONObject("""{"data":[{"id":"only-model"}]}""")
        val parsed = CopilotDeviceFlow.parseModelsDetailed(json)
        assertEquals(1, parsed.models.size)
        assertTrue(parsed.hiddenByPolicy.isEmpty())
    }

    @Test
    fun `policy-hidden and non-chat models are dropped and reported`() {
        val json = JSONObject(
            """
            {"data":[
              {"id":"chat-ok","capabilities":{"type":"chat"}},
              {"id":"hidden","model_picker_enabled":false},
              {"id":"embed","capabilities":{"type":"embeddings"}}
            ]}
            """.trimIndent(),
        )
        val parsed = CopilotDeviceFlow.parseModelsDetailed(json)
        assertEquals(listOf("chat-ok"), parsed.models.map { it.id })
        assertEquals(listOf("hidden"), parsed.hiddenByPolicy)
        assertEquals(listOf("embed:embeddings"), parsed.droppedByType)
        assertEquals(3, parsed.totalReturned)
    }

    @Test
    fun `unknown capability fields stay null rather than becoming false`() {
        val json = JSONObject("""{"data":[{"id":"m","capabilities":{"type":"chat"}}]}""")
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertNull(m.contextWindow)
        assertNull(m.maxOutputTokens)
        assertNull(m.supportsReasoning)
        assertFalse(m.supportsVision)
    }

    @Test
    fun `duplicate ids are collapsed`() {
        val json = JSONObject("""{"data":[{"id":"dup"},{"id":"dup"}]}""")
        assertEquals(1, CopilotDeviceFlow.parseModels(json).size)
    }

    @Test
    fun `a malformed payload yields an empty list rather than throwing`() {
        assertTrue(CopilotDeviceFlow.parseModels(JSONObject("""{"nope":1}""")).isEmpty())
    }


    private class FakeStore {
        val prefs = mutableMapOf<String, String>()
        fun copilotLogin(id: String) { prefs["oauth_copilot_github_token_$id"] = "gho_abc123" }
        fun genericLogin(id: String) { prefs["oauth_tokens_$id"] = """{"access_token":"tok"}""" }

        fun baseIsAuthenticated(id: String): Boolean {
            val blob = prefs["oauth_tokens_$id"] ?: return false
            return JSONObject(blob).optString("access_token", "").isNotEmpty()
        }
        fun copilotIsAuthenticated(id: String): Boolean =
            !prefs["oauth_copilot_github_token_$id"].isNullOrEmpty()
    }

    @Test
    fun `the inherited check cannot see a Copilot sign-in`() {
        val store = FakeStore()
        store.copilotLogin("i1")
        assertFalse("the store Copilot writes is not the one the base reads",
            store.baseIsAuthenticated("i1"))
    }

    @Test
    fun `Copilot's own check sees it`() {
        val store = FakeStore()
        store.copilotLogin("i1")
        assertTrue(store.copilotIsAuthenticated("i1"))
    }

    @Test
    fun `neither reports a signed-in state before any login`() {
        val store = FakeStore()
        assertFalse(store.baseIsAuthenticated("i1"))
        assertFalse(store.copilotIsAuthenticated("i1"))
    }

    @Test
    fun `the override does not disturb other providers`() {
        val store = FakeStore()
        store.genericLogin("i2")
        assertTrue(store.baseIsAuthenticated("i2"))
        assertFalse(store.copilotIsAuthenticated("i2"))
    }

    @Test
    fun `an expired session token does not revoke the sign-in`() {
        val store = FakeStore()
        store.copilotLogin("i1")
        store.prefs.remove("oauth_copilot_session_token_i1")
        assertTrue(store.copilotIsAuthenticated("i1"))
    }


    private fun hasAnyCredential(
        apiKey: String?,
        allowsEmptyApiKey: Boolean,
        managerSaysAuthenticated: Boolean,
        genericBlob: String?,
    ): Boolean {
        if (apiKey != null || allowsEmptyApiKey) return true
        if (managerSaysAuthenticated) return true
        return !genericBlob.isNullOrEmpty()
    }

    @Test
    fun `an OAuth provider with no API key is credentialed when its manager says so`() {
        assertTrue(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = false,
                managerSaysAuthenticated = true,
                genericBlob = null,
            ),
        )
    }

    @Test
    fun `the generic blob still answers for providers that use it`() {
        assertTrue(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = false,
                managerSaysAuthenticated = false,
                genericBlob = """{"access_token":"tok"}""",
            ),
        )
    }

    @Test
    fun `a signed-out instance is still reported as having nothing`() {
        assertFalse(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = false,
                managerSaysAuthenticated = false,
                genericBlob = null,
            ),
        )
    }

    @Test
    fun `a keyless compat endpoint remains credentialed`() {
        assertTrue(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = true,
                managerSaysAuthenticated = false,
                genericBlob = null,
            ),
        )
    }


    @Test
    fun `adaptive_thinking marks a model as reasoning-capable`() {
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":
               {"adaptive_thinking":true,"max_thinking_budget":32000}}}]}""".trimIndent(),
        )
        assertEquals(true, CopilotDeviceFlow.parseModels(json).single().supportsReasoning)
    }

    @Test
    fun `a reasoning_effort tier list also marks it capable, and is carried through`() {
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":
               {"reasoning_effort":["low","medium","high","xhigh","max"]}}}]}""".trimIndent(),
        )
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertEquals(true, m.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), m.reasoningEffortValues)
    }

    @Test
    fun `a model advertising neither signal is not reasoning-capable`() {
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":
               {"streaming":true,"tool_calls":true}}}]}""".trimIndent(),
        )
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertEquals(false, m.supportsReasoning)
        assertNull(m.reasoningEffortValues)
    }

    @Test
    fun `a missing supports block stays unknown rather than false`() {
        val json = JSONObject("""{"data":[{"id":"m","capabilities":{"type":"chat"}}]}""")
        assertNull(CopilotDeviceFlow.parseModels(json).single().supportsReasoning)
    }

    @Test
    fun `the legacy thinking field still works if it ever appears`() {
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":{"thinking":true}}}]}""",
        )
        assertEquals(true, CopilotDeviceFlow.parseModels(json).single().supportsReasoning)
    }
}
