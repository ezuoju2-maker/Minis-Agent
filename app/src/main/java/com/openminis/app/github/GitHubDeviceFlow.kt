package com.openminis.app.github

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

object GitHubDeviceFlow {

    private const val TAG = "GitHubDevice"

    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val OPEN_URL = "https://github.com/login/device"

    const val DEFAULT_CLIENT_ID = "Iv1.b507a08c87ecfe98"

    const val MAX_SCOPE =
        "repo workflow admin:org admin:repo_hook admin:public_key admin:gpg_key " +
            "admin:ssh_signing_key admin:org_hook user project gist notifications delete_repo " +
            "write:packages read:packages delete:packages read:audit_log codespace"

    const val READ_SCOPE = "read:user"

    data class Authorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    ) {
        val openUrl: String get() = verificationUriComplete ?: verificationUri
    }

    sealed class PollResult {
        data class Done(val token: String, val scopes: List<String>) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        data class Failed(val message: String) : PollResult()
    }

    fun parse(json: JSONObject): Authorization? {
        val deviceCode = json.optString("device_code", "").ifEmpty { return null }
        val userCode = json.optString("user_code", "").ifEmpty { return null }
        val uri = json.optString("verification_uri", "")
            .ifEmpty { json.optString("verification_url", "") }
            .ifEmpty { OPEN_URL }
        val complete = json.optString("verification_uri_complete", "")
            .ifEmpty { json.optString("verification_url_complete", "") }
            .ifEmpty { null }
        return Authorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            verificationUriComplete = complete,
            expiresInSeconds = json.optLong("expires_in", 0L).takeIf { it > 0 } ?: 900L,
            intervalSeconds = json.optLong("interval", 0L).takeIf { it > 0 } ?: 5L,
        )
    }

    fun classify(json: JSONObject, httpOK: Boolean): PollResult {
        if (httpOK) {
            val token = json.optString("access_token", "")
            if (token.isNotEmpty()) {
                val scopes = json.optString("scope", "")
                    .split(',', ' ')
                    .filter { it.isNotBlank() }
                return PollResult.Done(token, scopes)
            }
        }
        val error = json.optString("error", "").lowercase().ifEmpty { if (httpOK) "unknown_error" else "unparsed" }
        val description = json.optString("error_description", "").ifEmpty { error }
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Failed("GitHub 上点了取消 / cancelled on GitHub")
            "expired_token" -> PollResult.Failed("验证码已过期，请重新发起 / the code expired, start again")
            "device_flow_disabled" ->
                PollResult.Failed("该 OAuth App 未开启 Device Flow / device flow is disabled for this client id")
            "incorrect_client_credentials" ->
                PollResult.Failed("client id 无效 / invalid client id")
            "unsupported_grant_type" ->
                PollResult.Failed("token 端点不接受 device flow / grant type rejected")
            else -> PollResult.Failed(description)
        }
    }

    private fun post(url: String, fields: Map<String, String>): Pair<Int, JSONObject> {
        val body = fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val response = com.openminis.app.network.NetworkUrlRequest.open(
            tag = com.openminis.app.network.NetworkHttp.DEFAULT_TAG,
            url = url,
            method = "POST",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            body = body.toByteArray(),
            connectTimeoutMs = 20000,
            readTimeoutMs = 20000,
        )
        val text = runCatching { response.text() }.getOrNull().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrElse {
            if (text.contains("access_token=")) formToJson(text) else JSONObject()
        }
        val code = response.code
        runCatching { response.close() }
        return code to json
    }

    private fun formToJson(body: String): JSONObject {
        val obj = JSONObject()
        for (pair in body.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = dec(pair.substring(0, idx))
            val value = dec(pair.substring(idx + 1))
            if (obj.has(key)) continue
            obj.put(key, value)
        }
        return obj
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun dec(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    suspend fun start(clientId: String, scope: String): Authorization = withContext(Dispatchers.IO) {
        val effective = clientId.ifBlank { DEFAULT_CLIENT_ID }
        val (status, json) = post(
            DEVICE_CODE_URL,
            mapOf("client_id" to effective, "scope" to scope),
        )
        if (status !in 200..299) {
            val desc = json.optString("error_description", "")
                .ifEmpty { json.optString("error", "device authorization failed") }
            throw Exception("GitHub 授权发起失败 / device authorization failed: $desc")
        }
        parse(json) ?: throw Exception("GitHub 返回的设备码不完整 / malformed device response")
    }

    suspend fun await(
        clientId: String,
        auth: Authorization,
        onPoll: (PollResult) -> Unit = {},
    ): PollResult.Done = withContext(Dispatchers.IO) {
        val effective = clientId.ifBlank { DEFAULT_CLIENT_ID }
        val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000L
        var interval = auth.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val (status, json) = post(
                ACCESS_TOKEN_URL,
                mapOf(
                    "client_id" to effective,
                    "device_code" to auth.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            val result = classify(json, status in 200..299)
            Log.i(TAG, "poll http=$status result=${result.javaClass.simpleName}")
            onPoll(result)
            when (result) {
                is PollResult.Done -> return@withContext result
                PollResult.Pending -> Unit
                PollResult.SlowDown -> interval += 5L
                is PollResult.Failed -> throw Exception(result.message)
            }
        }
        throw Exception("验证码已过期，请重新发起 / the code expired, start again")
    }
}
