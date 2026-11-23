package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class XAIOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "XAIOAuth"

        const val OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
        const val CALLBACK_PORT = 56121
        const val CALLBACK_PATH = "/callback"

        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: com.openminis.app.data.repository.ProviderRepository,
        ): String {
            val manager = XAIOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }


    override val authURL = "https://auth.x.ai/oauth/authorize"
    override val tokenURL = "https://auth.x.ai/oauth/token"
    override val clientId = OAUTH_CLIENT_ID
    override val clientSecret: String? = null
    override val callbackPort = CALLBACK_PORT
    override val redirectPath = CALLBACK_PATH
    override val scopes = "openid profile email offline_access grok-cli:access api:access"

    override val redirectUri: String get() = "http://127.0.0.1:$callbackPort$redirectPath"

    var accountId: String?
        get() = loadOAuthString("account_id")
        private set(value) { value?.let { saveOAuthString("account_id", it) } }

    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    var displayName: String?
        get() = loadOAuthString("display_name")
        private set(value) { value?.let { saveOAuthString("display_name", it) } }

    private var loginCallbackServer: OAuthCallbackServer? = null

    @Volatile private var isAuthenticating: Boolean = false


    override fun generateAndSavePKCE(): Pair<String, String> {
        val (verifier, challenge) = generateHexPKCE()
        saveOAuthString("verifier", verifier)
        saveOAuthString("challenge", challenge)
        return verifier to challenge
    }


    private suspend fun fetchDiscoveryEndpoints(): Pair<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(DISCOVERY_URL).get().build()
        val resp = httpClient.newCall(req).execute()
        val code = resp.code
        val body = resp.body?.string() ?: ""
        resp.close()
        if (code !in 200..299 || body.isEmpty()) {
            throw IllegalStateException("xAI OIDC discovery failed: HTTP $code")
        }
        val json = JSONObject(body)
        val authEndpoint = json.optString("authorization_endpoint", "")
        val tokenEndpoint = json.optString("token_endpoint", "")
        if (authEndpoint.isEmpty() || tokenEndpoint.isEmpty()) {
            throw IllegalStateException("xAI OIDC discovery missing endpoints")
        }
        require(Uri.parse(authEndpoint).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted authorization endpoint: $authEndpoint"
        }
        require(Uri.parse(tokenEndpoint).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted token endpoint: $tokenEndpoint"
        }
        saveOAuthString("auth_endpoint", authEndpoint)
        saveOAuthString("token_endpoint", tokenEndpoint)
        authEndpoint to tokenEndpoint
    }

    private suspend fun resolveAuthEndpoint(): String =
        loadOAuthString("auth_endpoint")?.takeIf { it.isNotEmpty() }
            ?: fetchDiscoveryEndpoints().first

    private suspend fun resolveTokenEndpoint(): String =
        loadOAuthString("token_endpoint")?.takeIf { it.isNotEmpty() }
            ?: fetchDiscoveryEndpoints().second


    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private suspend fun buildXAIAuthorizationUrl(): String {
        val (_, challenge) = generateAndSavePKCE()
        val state = generateState()
        saveOAuthString("state", state)
        val nonce = generateNonce()
        val authEndpoint = resolveAuthEndpoint()
        return "$authEndpoint?" + listOf(
            "response_type=code",
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "nonce=$nonce",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "plan=generic",
            "referrer=minis",
        ).joinToString("&")
    }


    override fun buildTokenParams(code: String): Map<String, String> {
        return mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to (loadOAuthString("verifier") ?: ""),
            "code_challenge" to (loadOAuthString("challenge") ?: ""),
            "code_challenge_method" to "S256",
        )
    }

    private suspend fun exchangeCodeForToken(code: String): String = withContext(Dispatchers.IO) {
        val tokenEndpoint = resolveTokenEndpoint()
        val params = buildTokenParams(code)
        val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val req = Request.Builder()
            .url(tokenEndpoint)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val resp = httpClient.newCall(req).execute()
        val code0 = resp.code
        val body = resp.body?.string() ?: ""
        resp.close()
        if (code0 !in 200..299) {
            Log.e(TAG, "xAI token exchange failed: $code0 ${OAuthManager.sanitizeBody(body)}")
            throw Exception("xAI token exchange failed ($code0): $body")
        }
        val json = JSONObject(body)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            throw Exception("xAI token exchange: no access_token in response")
        }
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveTokensJson(json)
        onTokensReceived(json)
        Log.i(TAG, "xAI token exchange OK, expires in ${expiresIn}s, has refresh: ${json.has("refresh_token")}")
        accessToken
    }

    private fun saveTokensJson(json: JSONObject) {
        val pref = context.getSharedPreferences("dummy", Context.MODE_PRIVATE)
        saveOAuthString("tokens", json.toString())
    }

    override suspend fun onTokensReceived(json: JSONObject) {
        val idToken = json.optString("id_token", "")
        if (idToken.isNotEmpty()) parseIdToken(idToken)
        runCatching { resolveTokenEndpoint() }
    }

    private fun parseIdToken(idToken: String) {
        try {
            val parts = idToken.split(".")
            if (parts.size < 2) return
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            val json = JSONObject(payload)
            json.optString("email").takeIf { it.isNotEmpty() }?.let { email = it }
            json.optString("name").takeIf { it.isNotEmpty() }?.let { displayName = it }
            (json.optString("account_id").takeIf { it.isNotEmpty() }
                ?: json.optString("sub").takeIf { it.isNotEmpty() })?.let { accountId = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xAI id_token: ${e.message}")
        }
    }


    override suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val stored = JSONObject(
            loadOAuthString("tokens").takeUnless { it.isNullOrEmpty() }
                ?: return@withContext false,
        )
        val refresh = stored.optString("refresh_token", "").ifEmpty { return@withContext false }
        try {
            val tokenEndpoint = resolveTokenEndpoint()
            val params = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to clientId,
            )
            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val req = Request.Builder()
                .url(tokenEndpoint)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()
            if (code == 403) {
                Log.w(TAG, "xAI refresh returned 403 — preserving token (subscription tier issue)")
                return@withContext false
            }
            if (code !in 200..299) {
                Log.e(TAG, "xAI refresh failed: $code")
                if (isRefreshRejected(code, body, DEFAULT_FATAL_REFRESH_CODES)) markNeedsReauth(refresh)
                return@withContext false
            }
            val json = JSONObject(body)
            if (!json.has("refresh_token")) json.put("refresh_token", refresh)
            val expiresIn = json.optLong("expires_in", 0)
            if (expiresIn > 0) {
                json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
            }
            saveTokensJson(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "xAI refresh error", e)
            false
        }
    }


    suspend fun performLogin(context: Context): String {
        if (isAuthenticating) {
            Log.w(TAG, "xAI login already in progress — ignoring re-entrant call (instance: $instanceId)")
            while (isAuthenticating) {
                kotlinx.coroutines.delay(100)
            }
            return loadOAuthString("tokens")?.let { JSONObject(it).optString("access_token", "") }
                ?.takeIf { it.isNotEmpty() }
                ?: throw Exception("xAI login: prior re-entrant call finished without a token")
        }
        isAuthenticating = true
        try {
            Log.i(TAG, "=== xAI OAuth login started (instance: $instanceId) ===")

            loginCallbackServer?.stop()
            loginCallbackServer = null

            val authUrl = buildXAIAuthorizationUrl()
            val expectedState = loadOAuthString("state") ?: ""

            val accessToken = withContext(Dispatchers.IO) {
                val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                    val server = OAuthCallbackServer(
                        callbackPort,
                        onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) },
                    ) { receivedCode, receivedState ->
                        if (cont.isActive) {
                            loginCallbackServer?.onExternalCancel = null
                            cont.resume(receivedCode to receivedState)
                        }
                    }
                    server.onExternalCancel = {
                        if (cont.isActive) {
                            cont.cancel(kotlinx.coroutines.CancellationException("xAI OAuth callback cancelled"))
                        }
                    }
                    loginCallbackServer = server
                    server.start()
                    Log.i(TAG, "Callback server started on port $callbackPort")

                    cont.invokeOnCancellation {
                        server.stop()
                        loginCallbackServer = null
                    }

                    OAuthTabLauncher.launch(context, Uri.parse(authUrl))
                    Log.i(TAG, "Opened Custom Tab for xAI authorization")

                    armDismissalDetector()
                }

                loginCallbackServer?.stop()
                loginCallbackServer = null
                Log.i(TAG, "xAI callback received — code length: ${code.length}")

                if (expectedState.isNotEmpty() && state != null && state != expectedState) {
                    throw Exception("xAI OAuth state mismatch — possible CSRF, refusing to exchange")
                }

                OAuthForegroundGate.awaitForeground(TAG)
                exchangeCodeForToken(code)
            }

            Log.i(TAG, "=== xAI OAuth login complete (instance: $instanceId) ===")
            return accessToken
        } finally {
            isAuthenticating = false
            disarmDismissalDetector()
        }
    }


    private var dismissalObserver: DefaultLifecycleObserver? = null

    private fun armDismissalDetector() {
        disarmDismissalDetector()
        val observer = object : DefaultLifecycleObserver {
            @Volatile var sawBackgrounded = false

            override fun onPause(owner: LifecycleOwner) { sawBackgrounded = true }
            override fun onStop(owner: LifecycleOwner) { sawBackgrounded = true }

            override fun onResume(owner: LifecycleOwner) {
                if (!sawBackgrounded) return
                GlobalScope.launch {
                    delay(1500)
                    val server = loginCallbackServer ?: return@launch
                    Log.w(TAG, "App resumed without OAuth callback — treating as user dismissal, stopping server")
                    server.stop()
                }
            }
        }
        dismissalObserver = observer
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            }
        } catch (e: Exception) {
            Log.w(TAG, "armDismissalDetector failed to attach observer: ${e.message}")
        }
    }

    private fun disarmDismissalDetector() {
        val observer = dismissalObserver ?: return
        dismissalObserver = null
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            }
        } catch (_: Exception) {}
    }
}
