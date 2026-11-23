package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GeminiOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "GeminiOAuth"
        private const val USER_AGENT = "GeminiCLI/0.30.0 (android; aarch64)"

        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }
    }

    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val authURL = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenURL = "https://oauth2.googleapis.com/token"
    override val clientId = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    override val clientSecret = "GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxxxxx"
    override val callbackPort = 8085
    override val redirectPath = "/oauth2callback"
    override val scopes = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"

    override fun buildAuthorizationUrl(): String {
        val (_, challenge) = generateHexPKCE()
        val state = generateState()
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "access_type=offline",
            "prompt=consent",
        ).joinToString("&")
    }

    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    var gcpProjectId: String?
        get() = loadOAuthString("gcp_project")
        private set(value) { value?.let { saveOAuthString("gcp_project", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        fetchUserEmail()
    }

    private suspend fun fetchUserEmail() {
        val token = validAccessToken() ?: return
        try {
            com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://www.googleapis.com/oauth2/v1/userinfo",
                headers = mapOf("Authorization" to "Bearer $token")).use { r ->
                if (r.code == 200) email = JSONObject(r.text()).optString("email")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch email", e)
        }
    }

    suspend fun discoverProjectIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (!gcpProjectId.isNullOrEmpty()) return@withContext true
        val token = validAccessToken() ?: return@withContext false

        val projectId = loadCodeAssist(token) ?: onboardUser(token)
        if (projectId != null) {
            gcpProjectId = projectId
            return@withContext true
        }
        false
    }

    private fun cloudCodeHeaders(token: String, json: Boolean): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("User-Agent", USER_AGENT)
        if (json) put("Content-Type", "application/json")
    }

    private fun loadCodeAssist(token: String): String? {
        return try {
            val response = com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", "POST",
                cloudCodeHeaders(token, json = true), "{}".toByteArray()).use { r -> if (r.code == 200) r.text() else null }
            response?.let { JSONObject(it).optString("gcpProjectId").ifEmpty { null } }
        } catch (e: Exception) {
            Log.d(TAG, "loadCodeAssist failed: ${e.message}")
            null
        }
    }

    private suspend fun onboardUser(token: String): String? {
        return try {
            val body = com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://cloudcode-pa.googleapis.com/v1internal:onboardUser", "POST",
                cloudCodeHeaders(token, json = true),
                """{"setupMetadata":{"ideType":"NEOVIM","platforms":["LINUX"]}}""".toByteArray(),
            ).use { r -> if (r.code == 200) r.text() else null }

            if (body != null) {
                val json = JSONObject(body)
                val opName = json.optString("name")
                if (opName.isNotEmpty()) {
                    pollOperation(token, opName)
                } else {
                    json.optString("gcpProjectId").ifEmpty { null }
                }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "onboardUser failed", e)
            null
        }
    }

    private suspend fun pollOperation(token: String, operationName: String): String? {
        for (i in 0 until 24) {
            delay(5000)
            try {
                val body = com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://cloudcode-pa.googleapis.com/v1internal/$operationName",
                    headers = cloudCodeHeaders(token, json = false)).use { r -> if (r.code == 200) r.text() else null }
                if (body != null) {
                    val json = JSONObject(body)
                    if (json.optBoolean("done")) {
                        return json.optJSONObject("response")?.optString("gcpProjectId")?.ifEmpty { null }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }


    override suspend fun refreshToken(): Boolean =
        refreshTokenClassified() == RefreshOutcome.SUCCESS

    suspend fun refreshTokenClassified(): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            val stored = loadStoredTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                Log.d(TAG, "Refresh skipped — fresher token already present (concurrent refresh coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                val params = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshTokenValue,
                    "client_id" to clientId,
                    "client_secret" to (clientSecret ?: ""),
                )
                val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
                val request = okhttp3.Request.Builder()
                    .url(tokenURL)
                    .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseBody)
                    if (!json.has("refresh_token")) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Gemini token refresh successful. Expires in ${expiresIn}s")
                    return@withLock RefreshOutcome.SUCCESS
                }

                val bodyLower = responseBody.lowercase()
                val isInvalidGrant = responseCode == 400 || responseCode == 401 || responseCode == 403 ||
                    bodyLower.contains("invalid_grant") ||
                    bodyLower.contains("invalid_token")
                if (isInvalidGrant) {
                    Log.e(TAG, "Refresh token invalid ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — marking for re-login")
                    markNeedsReauth(refreshTokenValue)
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                Log.w(TAG, "Gemini token refresh transient failure ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                Log.w(TAG, "Gemini token refresh transient error — keeping token", e)
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext token

        when (refreshTokenClassified()) {
            RefreshOutcome.SUCCESS ->
                loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null
            RefreshOutcome.TRANSIENT ->
                if (expireAt > 0 && now >= expireAt) null else token
            RefreshOutcome.NO_TOKEN -> null
        }
    }
}
