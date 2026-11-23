package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ClaudeOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "ClaudeOAuth"

        val ANTHROPIC_OAUTH_IDENTIFIER_PROMPT: String
            get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.ifEmpty {
                throw IllegalStateException(
                    "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy "
                        + "app/provider-customization.properties.example to "
                        + "app/provider-customization.properties and set "
                        + "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before using Claude Code OAuth."
                )
            }

        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        private var callbackServer: OAuthCallbackServer? = null

        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        suspend fun login(context: Context, instanceId: String, providerRepository: ProviderRepository): String {
            val manager = ClaudeOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    enum class RefreshOutcome {
        SUCCESS,
        INVALID_GRANT,
        TRANSIENT,
        NO_TOKEN,
    }

    override val authURL = "https://claude.ai/oauth/authorize"
    override val tokenURL = "https://claude.ai/v1/oauth/token"
    override val clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val clientSecret: String? = null
    override val callbackPort = 54545
    override val redirectPath = "/callback"
    override val scopes = "org:create_api_key user:profile user:inference"

    suspend fun performLogin(context: Context): String {
        Log.i(TAG, "=== Anthropic OAuth login started (instance: $instanceId) ===")

        callbackServer?.stop()
        callbackServer = null

        val authUrl = buildAuthorizationUrl()

        val accessToken = withContext(Dispatchers.IO) {
            val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                val server = OAuthCallbackServer(
                    callbackPort,
                    onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) },
                ) { receivedCode, receivedState ->
                    if (cont.isActive) {
                        cont.resume(receivedCode to receivedState)
                    }
                }
                callbackServer = server
                server.start()
                Log.i(TAG, "Callback server started on port $callbackPort")

                cont.invokeOnCancellation {
                    server.stop()
                    callbackServer = null
                }

                OAuthTabLauncher.launch(context, Uri.parse(authUrl))
                Log.i(TAG, "Opened Custom Tab for Anthropic authorization")
            }

            callbackServer?.stop()
            callbackServer = null
            Log.i(TAG, "Callback received — code length: ${code.length}")

            val savedState = loadOAuthString("state")
            if (state != null && state != savedState) {
                throw Exception("OAuth state mismatch — possible CSRF attack")
            }

            OAuthForegroundGate.awaitForeground(TAG)
            exchangeCodeJson(code)
        }

        Log.i(TAG, "=== Anthropic OAuth login complete (instance: $instanceId) ===")
        return accessToken
    }

    private fun exchangeCodeJson(code: String): String {
        val body = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", loadOAuthString("verifier") ?: "")
            put("state", loadOAuthString("state") ?: "")
        }

        Log.d(TAG, "Token exchange: POST $tokenURL")

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .applyClaudeCliMimicryHeaders()
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        var response: okhttp3.Response? = null
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                response = httpClient.newCall(request).execute()
                lastError = null
                break
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Token exchange attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                lastError = e
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        if (response == null) {
            val e = lastError ?: Exception("Token exchange failed")
            throw if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) {
                OAuthNetworkUnreachableException(e)
            } else {
                e
            }
        }
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()

        if (responseCode !in 200..299) {
            Log.e(TAG, "Token exchange failed: $responseCode ${OAuthManager.sanitizeBody(responseBody)}")
            throw Exception("Token exchange failed ($responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            throw Exception("No access_token in response")
        }

        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveOAuthString("tokens", json.toString())

        Log.i(TAG, "Token exchange successful. Expires in ${expiresIn}s, has refresh: ${json.has("refresh_token")}")
        return accessToken
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
                val body = JSONObject().apply {
                    put("grant_type", "refresh_token")
                    put("client_id", clientId)
                    put("refresh_token", refreshTokenValue)
                }

                Log.d(TAG, "Refreshing OAuth token via JSON POST to $tokenURL")
                val request = okhttp3.Request.Builder()
                    .url(tokenURL)
                    .applyClaudeCliMimicryHeaders()
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
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
                    Log.i(TAG, "Token refresh successful. Expires in ${expiresIn}s")
                    return@withLock RefreshOutcome.SUCCESS
                }

                val isInvalidGrant = OAuthManager.isRefreshRejected(
                    responseCode, responseBody,
                    setOf("invalid_grant", "invalid_token", "invalid_request", "unauthorized_client"),
                )
                if (isInvalidGrant) {
                    Log.e(TAG, "Refresh token invalid ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — marking for re-login")
                    markNeedsReauth(refreshTokenValue)
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                Log.w(TAG, "Token refresh transient failure ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh transient error — keeping token", e)
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
            RefreshOutcome.INVALID_GRANT -> {
                null
            }
            RefreshOutcome.TRANSIENT -> {
                if (expireAt > 0 && now >= expireAt) null else token
            }
            RefreshOutcome.NO_TOKEN -> null
        }
    }

    override fun generateAndSavePKCE(): Pair<String, String> {
        val (verifier, challenge) = generatePKCE(byteLength = 96)
        saveOAuthString("verifier", verifier)
        return verifier to challenge
    }

    override fun generateAndSaveState(): String {
        val state = super.generateAndSaveState()
        saveOAuthString("state", state)
        return state
    }
}
