package com.openminis.app.github

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDeviceFlowTest {

    @Test
    fun deviceAuthorizationParsesGitHubFields() {
        val json = JSONObject(
            """
            {
              "device_code": "dc_123",
              "user_code": "ABCD-1234",
              "verification_uri": "https://github.com/login/device",
              "expires_in": 900,
              "interval": 5
            }
            """.trimIndent(),
        )
        val auth = GitHubDeviceFlow.parse(json)
        assertNotNull(auth)
        assertEquals("dc_123", auth!!.deviceCode)
        assertEquals("ABCD-1234", auth.userCode)
        assertEquals("https://github.com/login/device", auth.verificationUri)
        assertEquals(900L, auth.expiresInSeconds)
        assertEquals(5L, auth.intervalSeconds)
        assertEquals("https://github.com/login/device", auth.openUrl)
    }

    @Test
    fun completeUriWinsAndDefaultsApply() {
        val json = JSONObject(
            "{\"device_code\":\"d\",\"user_code\":\"u\"," +
                "\"verification_uri\":\"https://github.com/login/device\"," +
                "\"verification_uri_complete\":\"https://github.com/login/device?user_code=u\"}",
        )
        val auth = GitHubDeviceFlow.parse(json)!!
        assertEquals("https://github.com/login/device?user_code=u", auth.openUrl)
        assertEquals(900L, auth.expiresInSeconds)
        assertEquals(5L, auth.intervalSeconds)
    }

    @Test
    fun missingDeviceCodeIsRejected() {
        assertNull(GitHubDeviceFlow.parse(JSONObject("{\"user_code\":\"u\"}")))
        assertNull(GitHubDeviceFlow.parse(JSONObject("{}")))
    }

    @Test
    fun tokenResponseCarriesTokenAndScopes() {
        val result = GitHubDeviceFlow.classify(
            JSONObject("{\"access_token\":\"gho_x\",\"scope\":\"repo,workflow,user\",\"token_type\":\"bearer\"}"),
            true,
        )
        assertTrue(result is GitHubDeviceFlow.PollResult.Done)
        val done = result as GitHubDeviceFlow.PollResult.Done
        assertEquals("gho_x", done.token)
        assertEquals(listOf("repo", "workflow", "user"), done.scopes)
    }

    @Test
    fun pendingAndSlowDownAreRetryable() {
        assertTrue(
            GitHubDeviceFlow.classify(JSONObject("{\"error\":\"authorization_pending\"}"), true) ==
                GitHubDeviceFlow.PollResult.Pending,
        )
        assertTrue(
            GitHubDeviceFlow.classify(JSONObject("{\"error\":\"slow_down\"}"), true) ==
                GitHubDeviceFlow.PollResult.SlowDown,
        )
    }

    @Test
    fun terminalErrorsExplainThemselves() {
        val denied = GitHubDeviceFlow.classify(JSONObject("{\"error\":\"access_denied\"}"), true)
        assertTrue(denied is GitHubDeviceFlow.PollResult.Failed)
        val expired = GitHubDeviceFlow.classify(JSONObject("{\"error\":\"expired_token\"}"), true)
        assertTrue(expired is GitHubDeviceFlow.PollResult.Failed)
        val disabled = GitHubDeviceFlow.classify(JSONObject("{\"error\":\"device_flow_disabled\"}"), true)
        assertTrue(disabled is GitHubDeviceFlow.PollResult.Failed)
        assertTrue((disabled as GitHubDeviceFlow.PollResult.Failed).message.contains("Device Flow", true))
    }

    @Test
    fun successfulHttpWithoutTokenIsNotSuccess() {
        val result = GitHubDeviceFlow.classify(JSONObject("{\"ok\":true}"), true)
        assertTrue(result is GitHubDeviceFlow.PollResult.Failed)
    }

    @Test
    fun maxScopeCoversTheWholeToolbox() {
        val scopes = GitHubDeviceFlow.MAX_SCOPE.split(',', ' ').filter { it.isNotBlank() }
        assertTrue(scopes.containsAll(listOf("repo", "workflow", "admin:org", "delete_repo", "gist", "user")))
        assertTrue(GitHubDeviceFlow.READ_SCOPE.isNotBlank())
        assertTrue(GitHubDeviceFlow.DEFAULT_CLIENT_ID.startsWith("Iv1."))
    }
}
