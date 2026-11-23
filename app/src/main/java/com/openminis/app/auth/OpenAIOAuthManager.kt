package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ProxySelector
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OAuthNetworkUnreachableException(cause: Throwable) : Exception(cause.message, cause)

class OpenAIOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "CodexOAuth"

        internal fun claimsFromIdToken(token: String): IdTokenClaims? {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = JSONObject(String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            val auth = payload.optJSONObject("https://api.openai.com/auth")
            fun claim(name: String): String? =
                auth?.optString(name)?.takeIf { it.isNotEmpty() } ?: payload.optString(name).takeIf { it.isNotEmpty() }
            return IdTokenClaims(claim("chatgpt_account_id"), claim("chatgpt_plan_type"))
        }

        internal fun claimsFromTokens(json: JSONObject): IdTokenClaims? {
            fun of(field: String): IdTokenClaims? =
                json.optString(field, "").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { claimsFromIdToken(it) }.getOrNull() }
            val id = of("id_token")
            val access = of("access_token")
            val accountId = id?.accountId ?: access?.accountId
            val planType = id?.planType ?: access?.planType
            if (accountId == null && planType == null) return null
            val source = when {
                id?.accountId != null -> "id_token"
                access?.accountId != null -> "access_token"
                else -> null
            }
            return IdTokenClaims(accountId, planType, source)
        }

        suspend fun login(context: Context, instanceId: String, providerRepository: com.openminis.app.data.repository.ProviderRepository): String {
            val manager = OpenAIOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    private var loginCallbackServer: OAuthCallbackServer? = null
    private var expectedState: String? = null

    suspend fun performLogin(context: Context): String {
        AppLogger.info(TAG, "=== OAuth login started (instance=$instanceId) ===")

        loginCallbackServer?.stop()
        loginCallbackServer = null

        val authUrl = buildAuthorizationUrl()
        val redactedAuthUrl = OAuthLogRedaction.url(authUrl)
        AppLogger.info(TAG, "authorize URL: $redactedAuthUrl")
        AppLogger.info(TAG, "redirect_uri=$redirectUri callbackPort=$callbackPort")

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
                loginCallbackServer = server
                server.start()
                AppLogger.info(TAG, "callback server listening on port $callbackPort path=$redirectPath")

                cont.invokeOnCancellation {
                    server.stop()
                    loginCallbackServer = null
                }

                OAuthTabLauncher.launch(context, Uri.parse(authUrl))
                AppLogger.info(TAG, "Custom Tab launched")
            }

            loginCallbackServer?.stop()
            loginCallbackServer = null
            val stateMatches = expectedState != null && state == expectedState
            AppLogger.info(
                TAG,
                "callback received: codeLen=${code.length} state=${OAuthLogRedaction.secret(state)} expected=${OAuthLogRedaction.secret(expectedState)} match=$stateMatches",
            )
            if (!stateMatches) {
                AppLogger.warning(TAG, "state mismatch — proceeding anyway to mirror prior behaviour")
            }

            OAuthForegroundGate.awaitForeground(TAG)
            exchangeCodeJson(code)
        }

        AppLogger.info(TAG, "=== OAuth login complete (instance=$instanceId tokenLen=${accessToken.length}) ===")
        return accessToken
    }

    override val authURL = "https://auth.openai.com/oauth/authorize"
    override val tokenURL = "https://auth.openai.com/oauth/token"
    override val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val clientSecret: String? = null
    override val callbackPort = 1455
    override val redirectPath = "/auth/callback"
    override val scopes = "openid profile email offline_access"

    override fun buildAuthorizationUrl(): String {
        val (verifier, challenge) = generatePKCE()
        saveOAuthString("verifier", verifier)
        val state = generateState()
        expectedState = state
        AppLogger.debug(TAG, "PKCE: verifierLen=${verifier.length} stateLen=${state.length}")
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "codex_cli_simplified_flow=true",
            "originator=codex_cli_rs",
            "id_token_add_organizations=true",
        ).joinToString("&")
    }

    var accountId: String?
        get() = loadOAuthString("account_id") ?: backfill()?.accountId
        private set(value) { value?.let { saveOAuthString("account_id", it) } }

    var planType: String?
        get() = loadOAuthString("plan_type") ?: backfill()?.planType
        private set(value) { value?.let { saveOAuthString("plan_type", it) } }

    private fun backfill(): IdTokenClaims? {
        val claims = claimsFromTokens(loadStoredTokens() ?: return null) ?: return null
        var wrote = false
        if (claims.accountId != null && loadOAuthString("account_id") == null) { accountId = claims.accountId; wrote = true }
        if (claims.planType != null && loadOAuthString("plan_type") == null) { planType = claims.planType; wrote = true }
        if (wrote) AppLogger.info(TAG, "account claims backfilled (instance=$instanceId hasAccountId=${claims.accountId != null} source=${claims.source})")
        return claims
    }

    override suspend fun onTokensReceived(json: JSONObject) = recordAccountClaims(json)

    override suspend fun onTokensRefreshed(json: JSONObject) = recordAccountClaims(json)

    internal fun recordAccountClaims(json: JSONObject) {
        val claims = claimsFromTokens(json)
        claims?.accountId?.let { accountId = it }
        claims?.planType?.let { planType = it }
        AppLogger.info(TAG, "account claims (instance=$instanceId hasAccountId=${claims?.accountId != null} source=${claims?.source})")
    }

    private fun exchangeCodeJson(code: String): String {
        val jsonBody = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", loadOAuthString("verifier") ?: "")
        }

        AppLogger.info(TAG, "token exchange POST $tokenURL bodyLen=${jsonBody.toString().length}")
        logNetworkEnvironment(tokenURL)

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        var response: okhttp3.Response? = null
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                response = com.openminis.app.network.NetworkHttp.client(netTag, systemAwareHttpClient).newCall(request).execute()
                lastError = null
                break
            } catch (e: Exception) {
                AppLogger.warning(
                    TAG,
                    "token exchange attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                lastError = e
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        if (response == null) {
            AppLogger.error(
                TAG,
                "token exchange failed after 3 attempts: ${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            )
            throw lastError?.let { wrapIfNetworkError(it) } ?: Exception("Token exchange failed")
        }
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()
        AppLogger.info(
            TAG,
            "token exchange response: $responseCode bodyLen=${responseBody.length} body[0..500]=${OAuthLogRedaction.jsonBody(responseBody).take(500)}",
        )

        if (responseCode !in 200..299) {
            AppLogger.error(TAG, "token exchange non-2xx: $responseCode")
            throw Exception("Token exchange failed ($responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            AppLogger.error(TAG, "no access_token field in response")
            throw Exception("No access_token in response")
        }

        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveOAuthString("tokens", json.toString())

        recordAccountClaims(json)

        AppLogger.info(
            TAG,
            "token exchange OK accessTokenLen=${accessToken.length} expiresIn=${expiresIn}s hasRefresh=${json.has("refresh_token")}",
        )
        return accessToken
    }

    private fun wrapIfNetworkError(e: Throwable): Throwable =
        if (e is UnknownHostException || e is SocketTimeoutException) {
            OAuthNetworkUnreachableException(e)
        } else {
            e
        }

    private fun logNetworkEnvironment(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        try {
            val addr = InetAddress.getByName(host)
            AppLogger.info(TAG, "dns: $host → ${addr.hostAddress}")
        } catch (e: Exception) {
            AppLogger.warning(
                TAG,
                "dns lookup FAILED for $host: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
        try {
            val proxies = ProxySelector.getDefault().select(URI(url))
            AppLogger.info(TAG, "proxy chain for $host: $proxies")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "proxy lookup failed: ${e.message}")
        }
    }

    private val systemAwareHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .proxySelector(ProxySelector.getDefault())
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val route = chain.connection()?.route()
                AppLogger.info(
                    TAG,
                    "okhttp connect: url=${req.url} via proxy=${route?.proxy} socket=${route?.socketAddress}",
                )
                chain.proceed(req)
            }
            .build()
    }

    internal data class IdTokenClaims(val accountId: String?, val planType: String?, val source: String? = null)
}
