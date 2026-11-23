package com.openminis.app.auth

import org.json.JSONObject

object KimiDeviceFlow {

    const val AUTH_HOST = "https://auth.kimi.com"
    const val DEVICE_AUTHORIZATION_URL = "$AUTH_HOST/api/oauth/device_authorization"
    const val TOKEN_URL = "$AUTH_HOST/api/oauth/token"

    const val CODING_API_BASE = "https://api.kimi.com/coding"

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    ) {
        val openUrl: String get() = verificationUriComplete ?: verificationUri
    }

    fun parseDeviceAuthorization(json: JSONObject): DeviceAuthorization? {
        val deviceCode = json.optString("device_code", "").ifEmpty { return null }
        val userCode = json.optString("user_code", "").ifEmpty { return null }
        val uri = json.optString("verification_uri", "")
            .ifEmpty { json.optString("verification_url", "") }
            .ifEmpty { return null }
        val uriComplete = json.optString("verification_uri_complete", "")
            .ifEmpty { json.optString("verification_url_complete", "") }
            .ifEmpty { null }
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            verificationUriComplete = uriComplete,
            expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 } ?: 900L,
            intervalSeconds = json.optLong("interval", 0).takeIf { it > 0 } ?: 5L,
        )
    }

    sealed class PollResult {
        data class Success(
            val accessToken: String,
            val refreshToken: String?,
            val expiresInSeconds: Long?,
        ) : PollResult()

        object Pending : PollResult()

        object SlowDown : PollResult()

        data class Denied(val description: String) : PollResult()
        data class Expired(val description: String) : PollResult()
        data class Fatal(val description: String) : PollResult()
    }

    fun classifyPoll(json: JSONObject, httpOK: Boolean): PollResult {
        if (httpOK) {
            val access = json.optString("access_token", "")
            if (access.isNotEmpty()) {
                return PollResult.Success(
                    accessToken = access,
                    refreshToken = json.optString("refresh_token", "").ifEmpty { null },
                    expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 },
                )
            }
        }
        val error = json.optString("error", "").lowercase().ifEmpty { "unknown_error" }
        val description = json.optString("error_description", "").ifEmpty { error }
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Denied(description)
            "expired_token" -> PollResult.Expired(description)
            else -> PollResult.Fatal(description)
        }
    }

    fun bumpedInterval(currentSeconds: Long): Long = currentSeconds + 5

    fun shouldDeleteAfterInvalidGrant(
        staleRefreshToken: String,
        currentStoredRefreshToken: String?,
    ): Boolean {
        val current = currentStoredRefreshToken ?: return true
        return current == staleRefreshToken
    }
}
