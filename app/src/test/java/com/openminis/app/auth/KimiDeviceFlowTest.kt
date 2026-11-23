package com.openminis.app.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiDeviceFlowTest {


    @Test
    fun `parse full device authorization response`() {
        val json = JSONObject(
            """{"device_code":"dc1","user_code":"ABCD-1234",
                "verification_uri":"https://auth.kimi.com/device",
                "verification_uri_complete":"https://auth.kimi.com/device?code=ABCD-1234",
                "expires_in":600,"interval":7}""",
        )
        val auth = KimiDeviceFlow.parseDeviceAuthorization(json)!!
        assertEquals("dc1", auth.deviceCode)
        assertEquals("ABCD-1234", auth.userCode)
        assertEquals(600L, auth.expiresInSeconds)
        assertEquals(7L, auth.intervalSeconds)
        assertEquals("https://auth.kimi.com/device?code=ABCD-1234", auth.openUrl)
    }

    @Test
    fun `parse applies defaults and accepts verification_url spelling`() {
        val json = JSONObject(
            """{"device_code":"dc1","user_code":"XY",
                "verification_url":"https://auth.kimi.com/device"}""",
        )
        val auth = KimiDeviceFlow.parseDeviceAuthorization(json)!!
        assertEquals(900L, auth.expiresInSeconds)
        assertEquals(5L, auth.intervalSeconds)
        assertEquals("https://auth.kimi.com/device", auth.openUrl)
    }

    @Test
    fun `parse rejects missing required fields`() {
        assertNull(KimiDeviceFlow.parseDeviceAuthorization(JSONObject("""{"user_code":"X","verification_uri":"u"}""")))
        assertNull(KimiDeviceFlow.parseDeviceAuthorization(JSONObject("""{"device_code":"d","verification_uri":"u"}""")))
        assertNull(KimiDeviceFlow.parseDeviceAuthorization(JSONObject("""{"device_code":"d","user_code":"X"}""")))
    }


    @Test
    fun `poll success carries tokens`() {
        val r = KimiDeviceFlow.classifyPoll(
            JSONObject("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""),
            httpOK = true,
        ) as KimiDeviceFlow.PollResult.Success
        assertEquals("at", r.accessToken)
        assertEquals("rt", r.refreshToken)
        assertEquals(3600L, r.expiresInSeconds)
    }

    @Test
    fun `poll pending and slow_down keep the loop alive`() {
        assertTrue(
            KimiDeviceFlow.classifyPoll(JSONObject("""{"error":"authorization_pending"}"""), false)
                is KimiDeviceFlow.PollResult.Pending,
        )
        assertTrue(
            KimiDeviceFlow.classifyPoll(JSONObject("""{"error":"slow_down"}"""), false)
                is KimiDeviceFlow.PollResult.SlowDown,
        )
        assertEquals(10L, KimiDeviceFlow.bumpedInterval(5L))
    }

    @Test
    fun `poll terminal errors classified`() {
        assertTrue(
            KimiDeviceFlow.classifyPoll(JSONObject("""{"error":"access_denied"}"""), false)
                is KimiDeviceFlow.PollResult.Denied,
        )
        assertTrue(
            KimiDeviceFlow.classifyPoll(JSONObject("""{"error":"expired_token"}"""), false)
                is KimiDeviceFlow.PollResult.Expired,
        )
        val fatal = KimiDeviceFlow.classifyPoll(JSONObject("""{"error":"weird"}"""), false)
            as KimiDeviceFlow.PollResult.Fatal
        assertEquals("weird", fatal.description)
        assertTrue(
            KimiDeviceFlow.classifyPoll(JSONObject("{}"), true)
                is KimiDeviceFlow.PollResult.Fatal,
        )
    }


    @Test
    fun `delete only when stored refresh token still equals the failed one`() {
        assertFalse(KimiDeviceFlow.shouldDeleteAfterInvalidGrant("old-rt", "new-rt"))
        assertTrue(KimiDeviceFlow.shouldDeleteAfterInvalidGrant("old-rt", "old-rt"))
        assertTrue(KimiDeviceFlow.shouldDeleteAfterInvalidGrant("old-rt", null))
    }
}
