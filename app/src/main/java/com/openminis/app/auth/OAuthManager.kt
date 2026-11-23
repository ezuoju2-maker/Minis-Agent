package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

abstract class OAuthManager(
    protected val context: Context,
    protected val instanceId: String,
) {
    protected val netTag: String get() = com.openminis.app.network.NetworkRuntime.providerTag(instanceId)

    protected val httpClient: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(netTag, baseHttpClient)

    companion object {
        private const val TAG = "OAuthManager"
        private const val KEY_MANUAL_BEARER = "manual_bearer_token"

        val DEFAULT_FATAL_REFRESH_CODES = setOf(
            "invalid_grant", "invalid_token", "invalid_request",
            "unauthorized_client", "refresh_token_reused",
        )

        internal val baseHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        fun sanitizeBody(body: String, maxLen: Int = 300): String {
            val masked = Regex(
                "\"(access_token|refresh_token|id_token|api_key|key|client_secret|device_code|user_code|code_verifier)\"\\s*:\\s*\"[^\"]*\"",
            ).replace(body) { m -> "\"${m.groupValues[1]}\":\"***\"" }
            return if (masked.length <= maxLen) masked else masked.take(maxLen) + "…(${masked.length} chars)"
        }

        fun hasStoredCredential(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            if (!prefs.getString("oauth_tokens_$instanceId", null).isNullOrEmpty()) return true
            return !prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

        fun needsReauth(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            val mark = prefs.getString(needsReauthKey(instanceId), null) ?: return false
            val blob = prefs.getString("oauth_tokens_$instanceId", null) ?: return false
            if (tokenFingerprint(blob) != mark) return false
            return prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

        private fun needsReauthKey(instanceId: String) = "oauth_needs_reauth_$instanceId"

        private fun tokenFingerprint(blob: String): String =
            MessageDigest.getInstance("SHA-256").digest(blob.toByteArray())
                .joinToString("") { "%02x".format(it) }

        internal fun refreshedTokens(stored: JSONObject, response: JSONObject, usedRefreshToken: String): JSONObject {
            if (!response.has("refresh_token")) response.put("refresh_token", usedRefreshToken)
            val idToken = stored.optString("id_token", "")
            if (response.optString("id_token", "").isEmpty() && idToken.isNotEmpty()) response.put("id_token", idToken)
            return response
        }

        fun isRefreshRejected(status: Int, body: String, fatalErrorCodes: Set<String>): Boolean {
            if (status == 400 || status == 401 || status == 403) return true
            val code = try { JSONObject(body).optString("error", "") } catch (_: Exception) { "" }
            return code.lowercase() in fatalErrorCodes
        }

        fun forInstance(context: Context, instance: com.openminis.app.data.model.ProviderInstance): OAuthManager? {
            return when (instance.providerType) {
                com.openminis.app.data.model.ProviderType.anthropic -> ClaudeOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.openAI -> OpenAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.xAI -> XAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.kimiCode -> KimiOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.githubCopilot ->
                    CopilotOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.workBuddy ->
                    WorkBuddyOAuthManager(context, instance.id)
                else -> null
            }
        }
    }

    abstract val authURL: String
    abstract val tokenURL: String
    abstract val clientId: String
    abstract val clientSecret: String?
    abstract val callbackPort: Int
    abstract val redirectPath: String
    abstract val scopes: String

    open val redirectUri: String get() = "http://localhost:$callbackPort$redirectPath"

    private var codeVerifier: String? = null

    protected fun generatePKCE(): Pair<String, String> = generatePKCE(byteLength = 64)

    protected fun generatePKCE(byteLength: Int): Pair<String, String> {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateHexPKCE(): Pair<String, String> {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = bytes.joinToString("") { "%02x".format(it) }
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    open fun generateAndSavePKCE(): Pair<String, String> = generatePKCE()

    open fun generateAndSaveState(): String = generateState()

    private var currentState: String? = null
    private var callbackServer: OAuthCallbackServer? = null

    open fun buildAuthorizationUrl(): String {
        val (_, challenge) = generateAndSavePKCE()
        currentState = generateAndSaveState()
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$currentState",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
        ).joinToString("&")
    }

    suspend fun startLogin(onComplete: (Boolean) -> Unit) {
        callbackServer?.stop()
        callbackServer = OAuthCallbackServer(
            callbackPort,
            onError = { err ->
                Log.w(TAG, "OAuth callback error: ${err.error}")
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) { onComplete(false) }
            },
        ) { code, state ->
            if (state != null && state != currentState) {
                Log.w(TAG, "State mismatch")
                onComplete(false)
                return@OAuthCallbackServer
            }
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                OAuthForegroundGate.awaitForeground(TAG)
                val success = exchangeCode(code)
                withContext(Dispatchers.Main) { onComplete(success) }
            }
        }
        callbackServer?.start()

        val url = buildAuthorizationUrl()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    suspend fun exchangeCode(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = buildTokenParams(code)
            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token exchange failed: $responseCode ${sanitizeBody(responseBody)}")
                return@withContext false
            }

            val json = JSONObject(responseBody)
            saveTokens(json)
            onTokensReceived(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            false
        } finally {
            callbackServer?.stop()
            callbackServer = null
        }
    }

    protected open fun buildTokenParams(code: String): Map<String, String> {
        val params = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to (codeVerifier ?: ""),
        )
        clientSecret?.let { params["client_secret"] = it }
        return params
    }

    open suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext false
        val refreshToken = stored.optString("refresh_token", "").ifEmpty { return@withContext false }

        try {
            val params = mutableMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
            clientSecret?.let { params["client_secret"] = it }

            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token refresh failed: $responseCode")
                if (isRefreshRejected(responseCode, responseBody, DEFAULT_FATAL_REFRESH_CODES)) {
                    markNeedsReauth(refreshToken)
                }
                return@withContext false
            }

            val json = refreshedTokens(stored, JSONObject(responseBody), refreshToken)
            saveTokens(json)
            onTokensRefreshed(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            false
        }
    }

    open suspend fun validAccessToken(): String? {
        loadManualBearerToken()?.takeIf { it.isNotEmpty() }?.let { return it }

        val stored = loadStoredTokens() ?: return null
        val token = stored.optString("access_token", "").ifEmpty { return null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        if (expireAt > 0 && (expireAt - now) < 4 * 3600 * 1000) {
            if (refreshToken()) {
                return loadStoredTokens()?.optString("access_token")
            }
            if (expireAt > 0 && now >= expireAt) {
                Log.w(TAG, "Token expired and refresh failed — keeping credentials")
                return null
            }
        }
        return token
    }

    open fun isAuthenticated(): Boolean {
        if (loadManualBearerToken()?.isNotEmpty() == true) return true
        val stored = loadStoredTokens() ?: return false
        return stored.optString("access_token", "").isNotEmpty()
    }

    fun logout() {
        getEncryptedPrefs().edit()
            .remove("oauth_tokens_$instanceId")
            .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    fun needsReauth(): Boolean = needsReauth(context, instanceId)

    protected fun markNeedsReauth(staleRefreshToken: String) {
        val blob = loadOAuthString("tokens") ?: return
        val current = try { JSONObject(blob).optString("refresh_token", "") } catch (_: Exception) { "" }
        if (current != staleRefreshToken) {
            Log.w(TAG, "Stale refresh rejection ignored — token already rotated; not marking")
            return
        }
        getEncryptedPrefs().edit()
            .putString(needsReauthKey(instanceId), tokenFingerprint(blob))
            .apply()
        Log.w(TAG, "Refresh token rejected — marked for re-login, credentials kept")
    }

    private fun clearNeedsReauth() {
        getEncryptedPrefs().edit().remove(needsReauthKey(instanceId)).apply()
    }

    private fun saveTokens(json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", json.toString())
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    protected fun loadStoredTokens(): JSONObject? {
        val str = getEncryptedPrefs().getString("oauth_tokens_$instanceId", null) ?: return null
        return try { JSONObject(str) } catch (_: Exception) { null }
    }

    protected fun saveOAuthString(key: String, value: String) {
        getEncryptedPrefs().edit().putString("oauth_${key}_$instanceId", value).apply()
        if (key == "tokens") clearNeedsReauth()
    }

    protected fun loadOAuthString(key: String): String? {
        return getEncryptedPrefs().getString("oauth_${key}_$instanceId", null)
    }

    fun saveManualBearerToken(token: String) {
        saveOAuthString(KEY_MANUAL_BEARER, token)
        com.openminis.app.quota.QuotaStore.credentialsChanged()
    }

    fun loadManualBearerToken(): String? = loadOAuthString(KEY_MANUAL_BEARER)

    fun deleteManualBearerToken() {
        getEncryptedPrefs().edit()
            .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            .apply()
        com.openminis.app.quota.QuotaStore.credentialsChanged()
    }


    fun exportStoredTokensJson(): String? = loadStoredTokens()?.toString()

    fun importStoredTokensJson(json: String) {
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        val normalized = normalizeCamelToSnake(obj)
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", normalized.toString())
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    private fun normalizeCamelToSnake(obj: JSONObject): JSONObject {
        val keyMap = mapOf(
            "accessToken" to "access_token",
            "refreshToken" to "refresh_token",
            "idToken" to "id_token",
            "expireDate" to "expire_at",
            "lastRefresh" to "last_refresh",
            "accountId" to "account_id",
            "planType" to "plan_type",
            "tokenEndpoint" to "token_endpoint",
        )
        if (keyMap.keys.none { obj.has(it) }) return obj
        val result = JSONObject()
        for (key in obj.keys()) {
            val mapped = keyMap[key] ?: key
            var value: Any = obj.get(key)
            if (mapped == "expire_at" && value is Number) {
                val v = value.toDouble()
                val appleReferenceEpochMs = 978307200000L
                value = if (v < 2_000_000_000) {
                    (v * 1000).toLong() + appleReferenceEpochMs
                } else if (v < 2_000_000_000_000) {
                    v.toLong()
                } else {
                    v.toLong()
                }
            }
            result.put(mapped, value)
        }
        return result
    }

    fun exportOAuthString(key: String): String? = loadOAuthString(key)

    fun importOAuthString(key: String, value: String) = saveOAuthString(key, value)

    private fun getEncryptedPrefs() =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "oauth_prefs")

    protected open suspend fun onTokensReceived(json: JSONObject) {}

    protected open suspend fun onTokensRefreshed(json: JSONObject) {}
}
