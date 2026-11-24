package com.openminis.app.mcp.oauth

import android.content.Context
import android.net.Uri
import com.openminis.app.auth.OAuthCallbackServer
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MCPOAuthController(
    private val context: Context,
    private val secrets: McpOAuthSecrets = StoreMcpOAuthSecrets(context),
    dcrCache: McpDcrCache = PrefsMcpDcrCache(context),
    private val http: OkHttpClient = defaultHttp,
    private val bridgeDir: File = defaultBridgeDir(context),
    private val openBrowser: (String) -> Unit = { authUrl ->
        com.openminis.app.auth.OAuthTabLauncher.launch(context, Uri.parse(authUrl))
    },
    private val awaitForeground: suspend () -> Unit = {
        com.openminis.app.auth.OAuthForegroundGate.awaitForeground(TAG)
    },
    private val callbackTimeoutMs: Long = CALLBACK_TIMEOUT_MS,
    private val watchDismissal: (onDismissed: () -> Unit) -> (() -> Unit) = ::watchForReturnWithoutCallback,
) {

    val discovery = MCPOAuthDiscovery(secrets, dcrCache, http)

    sealed class Result {
        object Success : Result()

        object Cancelled : Result()

        object Denied : Result()

        object TimedOut : Result()

        object ClientRejected : Result()

        object GrantRejected : Result()
        data class Failed(val message: String) : Result()
    }

    enum class SignInPhase { DISCOVERING, REGISTERING_AGAIN, WAITING_FOR_BROWSER, FINISHING }

    sealed class SignInOutcome {
        data class SignedIn(val config: MCPOAuthConfig) : SignInOutcome()

        data class DiscoveryFailed(val error: McpDiscoveryException) : SignInOutcome()

        data class NotSignedIn(val result: Result) : SignInOutcome()
    }

    enum class Freshness {
        FRESH,

        RENEWED,

        NEEDS_SIGN_IN,

        UNKNOWN,
    }

    @Volatile
    private var callbackServer: OAuthCallbackServer? = null

    private sealed class Callback {
        data class Code(val code: String, val state: String?) : Callback()
        object Cancelled : Callback()
    }

    suspend fun authorize(
        server: String,
        serverUrl: String?,
        oauth: MCPOAuthConfig,
        expectedIssuer: String? = null,
        onExchange: () -> Unit = {},
    ): Result {
        if (!isBridgeSafeName(server)) {
            return Result.Failed("This server's name can't be used for sign-in. Rename it without '/' and try again.")
        }
        return try {
            authorizeUnchecked(server, serverUrl, oauth, expectedIssuer, onExchange)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Authorize] '$server' failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun authorizeUnchecked(
        server: String,
        serverUrl: String?,
        oauth: MCPOAuthConfig,
        expectedIssuer: String?,
        onExchange: () -> Unit,
    ): Result {
        if (!oauth.isConfigured) {
            return Result.Failed("OAuth is not fully configured (client id + endpoints required).")
        }
        val redirect = redirectUri(oauth)
        val redirectHost = runCatching { URI(redirect).host?.lowercase() }.getOrNull()
        if (redirectHost != "localhost" && redirectHost != "127.0.0.1") {
            return Result.Failed("Redirect URI must be a loopback address, e.g. $DEFAULT_REDIRECT_URI.")
        }
        val port = runCatching { URI(redirect).port.takeIf { it > 0 } }.getOrNull() ?: LOOPBACK_PORT
        val resource = McpPkce.canonicalResourceUri(serverUrl)
        val pkce = McpPkce.newPkce()
        val authUrl = McpPkce.buildAuthorizationUrl(
            authorizationEndpoint = oauth.authorizationEndpoint,
            clientId = oauth.clientId,
            redirectUri = redirect,
            pkce = pkce,
            resource = resource,
            scopes = oauth.scopes,
        )

        return withContext(Dispatchers.IO) {
            callbackServer?.stop()
            callbackServer = null

            var stopWatching: () -> Unit = {}
            val callback = try {
                withTimeoutOrNull(callbackTimeoutMs) {
                    suspendCancellableCoroutine<Callback> { cont ->
                        val srv = OAuthCallbackServer(
                            port,
                            onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) },
                        ) { code, state ->
                            if (cont.isActive) cont.resume(Callback.Code(code, state))
                        }
                        callbackServer = srv
                        srv.onExternalCancel = { if (cont.isActive) cont.resume(Callback.Cancelled) }
                        srv.start()
                        AppLogger.info(TAG, "[Authorize] '$server' callback server on :$port")

                        cont.invokeOnCancellation {
                            srv.stop()
                            callbackServer = null
                        }
                        openBrowser(authUrl)
                        AppLogger.info(TAG, "[Authorize] '$server' opened the authorization page")
                        stopWatching = watchDismissal {
                            if (callbackServer === srv) {
                                AppLogger.info(TAG, "[Authorize] '$server' Minis is back without an answer - the page was closed")
                                srv.stop()
                            }
                        }
                    }
                }
            } catch (e: com.openminis.app.auth.OAuthCallbackException) {
                AppLogger.warning(TAG, "[Authorize] '$server' callback error: ${e.callbackError.error}")
                return@withContext when (e.callbackError.error) {
                    "access_denied" -> Result.Denied
                    "invalid_client", "unauthorized_client" -> Result.ClientRejected
                    else -> Result.Failed(e.message ?: e.callbackError.error)
                }
            } finally {
                stopWatching()
                callbackServer?.stop()
                callbackServer = null
            }

            val answer = when (callback) {
                null -> {
                    AppLogger.error(TAG, "[Authorize] '$server' no answer from the sign-in page in ${callbackTimeoutMs / 1000}s")
                    return@withContext Result.TimedOut
                }
                Callback.Cancelled -> return@withContext Result.Cancelled
                is Callback.Code -> callback
            }
            if (answer.state != null && answer.state != pkce.state) {
                AppLogger.warning(TAG, "[Authorize] '$server' state mismatch")
                return@withContext Result.Failed("State mismatch in the OAuth callback.")
            }
            AppLogger.info(TAG, "[Authorize] '$server' answered (issuer=${expectedIssuer ?: "-"})")

            onExchange()
            awaitForeground()
            exchangeCode(server, oauth, redirect, resource, answer.code, pkce.verifier)
        }
    }

    private fun exchangeCode(
        server: String,
        oauth: MCPOAuthConfig,
        redirect: String,
        resource: String?,
        code: String,
        verifier: String,
    ): Result {
        val body = buildTokenExchangeBody(
            code = code,
            redirect = redirect,
            clientId = oauth.clientId,
            verifier = verifier,
            clientSecret = secrets.clientSecret(server),
            resource = resource,
        )
        val request = Request.Builder()
            .url(oauth.tokenEndpoint)
            .post(body.toRequestBody(FORM))
            .build()

        val (status, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Authorize] '$server' token exchange failed: ${t.message}")
            return Result.Failed("Token exchange failed: ${t.message}")
        }
        if (status >= 400) {
            return when (val failure = MCPOAuthSignInLogic.tokenFailure(text, status)) {
                MCPOAuthSignInLogic.TokenFailure.ClientRejected -> {
                    AppLogger.error(TAG, "[Authorize] '$server' token exchange HTTP $status: the client was rejected")
                    Result.ClientRejected
                }
                MCPOAuthSignInLogic.TokenFailure.GrantRejected -> {
                    AppLogger.error(TAG, "[Authorize] '$server' token exchange HTTP $status: invalid_grant - tokens cleared")
                    signOut(server)
                    Result.GrantRejected
                }
                is MCPOAuthSignInLogic.TokenFailure.Other -> {
                    AppLogger.error(TAG, "[Authorize] '$server' token exchange HTTP $status")
                    Result.Failed("Token exchange failed: ${failure.detail}")
                }
            }
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        val access = json?.optString("access_token", "").orEmpty()
        if (access.isEmpty()) return Result.Failed("Token endpoint returned no access_token.")
        val expiresIn = json?.optLong("expires_in", 0L) ?: 0L
        val stored = MCPOAuthStore.StoredTokens(
            accessToken = access,
            refreshToken = json?.optString("refresh_token", "")?.ifBlank { null },
            expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
        )
        secrets.setTokens(server, stored)
        materializeBridge(server, oauth, resource, stored)?.let { return Result.Failed(it) }
        AppLogger.info(TAG, "[Authorize] '$server' OK (hasRefresh=${stored.refreshToken != null}, expiresIn=${expiresIn}s)")
        return Result.Success
    }


    suspend fun signIn(
        server: String,
        serverUrl: String,
        persist: (MCPOAuthConfig) -> Unit,
        progress: (SignInPhase) -> Unit,
    ): SignInOutcome {
        var forceRegister = false
        var attempt = 0
        while (true) {
            progress(if (forceRegister) SignInPhase.REGISTERING_AGAIN else SignInPhase.DISCOVERING)
            val outcome = try {
                discovery.discover(serverUrl, DEFAULT_REDIRECT_URI, forceRegister)
            } catch (e: McpDiscoveryException) {
                AppLogger.info(TAG, "[SignIn] '$server' discovery ended: ${e.message}")
                return SignInOutcome.DiscoveryFailed(e)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[SignIn] '$server' discovery failed: ${e.javaClass.simpleName}: ${e.message}")
                return SignInOutcome.NotSignedIn(Result.Failed(e.message ?: e.javaClass.simpleName))
            }
            persist(outcome.config)
            secrets.setClientSecret(server, outcome.clientSecret)
            progress(SignInPhase.WAITING_FOR_BROWSER)
            val result = authorize(server, serverUrl, outcome.config, outcome.issuer) {
                progress(SignInPhase.FINISHING)
            }
            when {
                result is Result.Success -> return SignInOutcome.SignedIn(outcome.config)
                result is Result.ClientRejected &&
                    MCPOAuthSignInLogic.registersAgain(attempt, dynamic = true) -> {
                    AppLogger.info(TAG, "[SignIn] '$server': issuer=${outcome.issuer} no longer knows this client - registering again")
                    discovery.forgetClientId(outcome.issuer)
                    forceRegister = true
                    attempt += 1
                }
                else -> return SignInOutcome.NotSignedIn(result)
            }
        }
    }

    fun resetSignIn(server: String, clientId: String) {
        purge(server)
        val forgotten = discovery.forgetClient(clientId)
        AppLogger.info(TAG, "[SignIn] reset for '$server' (registrations forgotten: $forgotten)")
    }

    fun signOut(server: String) {
        secrets.signOut(server)
        bridgeFile(server)?.delete()
    }

    fun purge(server: String) {
        secrets.purge(server)
        bridgeFile(server)?.delete()
    }

    fun isAuthorized(server: String): Boolean = secrets.tokens(server)?.accessToken?.isNotEmpty() == true

    suspend fun freshness(server: String, oauth: MCPOAuthConfig, serverUrl: String?): Freshness =
        withContext(Dispatchers.IO) {
            var stored = secrets.tokens(server) ?: return@withContext Freshness.NEEDS_SIGN_IN
            var clientSecret = secrets.clientSecret(server)
            var resource = McpPkce.canonicalResourceUri(serverUrl)
            readBridge(server)?.let { b ->
                val access = b.optString("access_token", "")
                if (access.isNotEmpty()) {
                    stored = stored.copy(
                        accessToken = access,
                        refreshToken = b.optString("refresh_token", "").ifEmpty { stored.refreshToken },
                        expiresAtMs = if (b.has("expires_at")) b.optLong("expires_at") * 1000 else stored.expiresAtMs,
                    )
                    clientSecret = b.optString("client_secret", "").ifEmpty { clientSecret }
                    resource = b.optString("resource", "").ifEmpty { resource }
                }
            }
            if (stored.expiresAtMs <= 0 || System.currentTimeMillis() <= stored.expiresAtMs - 60_000) {
                return@withContext Freshness.FRESH
            }
            val refresh = stored.refreshToken
            if (refresh.isNullOrEmpty() || oauth.tokenEndpoint.isBlank() || oauth.clientId.isBlank()) {
                AppLogger.info(TAG, "[Refresh] '$server' expired and has nothing to renew it with")
                signOut(server)
                return@withContext Freshness.NEEDS_SIGN_IN
            }
            val form = linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to oauth.clientId,
            )
            if (!clientSecret.isNullOrEmpty()) form["client_secret"] = clientSecret!!
            if (!resource.isNullOrEmpty()) form["resource"] = resource!!
            val request = runCatching {
                Request.Builder().url(oauth.tokenEndpoint).post(formBody(form).toRequestBody(FORM)).build()
            }.getOrNull() ?: return@withContext Freshness.UNKNOWN
            val (status, text) = try {
                http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            } catch (t: Throwable) {
                AppLogger.info(TAG, "[Refresh] '$server' could not reach the token endpoint")
                return@withContext Freshness.UNKNOWN
            }
            if (status >= 400) {
                return@withContext when (MCPOAuthSignInLogic.tokenFailure(text, status)) {
                    MCPOAuthSignInLogic.TokenFailure.ClientRejected,
                    MCPOAuthSignInLogic.TokenFailure.GrantRejected -> {
                        AppLogger.error(TAG, "[Refresh] '$server' HTTP $status: the sign-in is no longer accepted - signed out")
                        signOut(server)
                        Freshness.NEEDS_SIGN_IN
                    }
                    is MCPOAuthSignInLogic.TokenFailure.Other -> {
                        AppLogger.error(TAG, "[Refresh] '$server' HTTP $status")
                        Freshness.UNKNOWN
                    }
                }
            }
            val obj = runCatching { JSONObject(text) }.getOrNull()
            val access = obj?.optString("access_token", "").orEmpty()
            if (access.isEmpty()) {
                AppLogger.error(TAG, "[Refresh] '$server' answered without an access_token")
                return@withContext Freshness.UNKNOWN
            }
            val expiresIn = obj?.optLong("expires_in", 0L) ?: 0L
            val renewed = MCPOAuthStore.StoredTokens(
                accessToken = access,
                refreshToken = obj?.optString("refresh_token", "")?.ifEmpty { null } ?: refresh,
                expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
            )
            secrets.setTokens(server, renewed)
            materializeBridge(server, oauth, resource, renewed)?.let {
                AppLogger.error(TAG, "[Refresh] '$server' renewed but the sandbox copy failed: $it")
            }
            AppLogger.info(TAG, "[Refresh] '$server' renewed (expiresIn=${expiresIn}s)")
            Freshness.RENEWED
        }

    fun refreshBridgeIfAuthorized(server: String, oauth: MCPOAuthConfig, serverUrl: String?) {
        val t = secrets.tokens(server) ?: return
        materializeBridge(server, oauth, McpPkce.canonicalResourceUri(serverUrl), t)?.let {
            AppLogger.error(TAG, "[Bridge] refresh for '$server' failed: $it")
        }
    }


    fun bridgeFile(server: String): File? {
        if (!isBridgeSafeName(server)) return null
        val file = File(bridgeDir, "$server.json")
        // Belt and braces: the file must sit directly in the bridge directory.
        val inDir = runCatching { file.canonicalFile.parentFile == bridgeDir.canonicalFile }.getOrDefault(false)
        return if (inDir) file else null
    }

    private fun readBridge(server: String): JSONObject? =
        bridgeFile(server)?.let { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }

    private fun materializeBridge(
        server: String,
        oauth: MCPOAuthConfig,
        resource: String?,
        tokens: MCPOAuthStore.StoredTokens,
    ): String? {
        val obj = JSONObject().apply {
            put("access_token", tokens.accessToken)
            put("expires_at", tokens.expiresAtMs / 1000)
            put("token_endpoint", oauth.tokenEndpoint)
            put("client_id", oauth.clientId)
            tokens.refreshToken?.let { put("refresh_token", it) }
            secrets.clientSecret(server)?.takeIf { it.isNotEmpty() }?.let { put("client_secret", it) }
            resource?.let { put("resource", it) }
        }
        val file = bridgeFile(server) ?: run {
            AppLogger.error(TAG, "[Bridge] refused server name '$server': not a file name in the bridge directory")
            return "This server's name can't be used for sign-in. Rename it without '/' and try again."
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(obj.toString())
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("could not move ${tmp.name} into place")
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Bridge] write failed for '$server': ${e.message}")
            return "Could not save the authorization for the sandbox to read. (${e.message})"
        }
        val readBack = readBridge(server)?.optString("access_token", "")
        if (readBack != tokens.accessToken) {
            AppLogger.error(TAG, "[Bridge] verify failed for '$server' at ${file.path}")
            return "The saved authorization could not be verified."
        }
        AppLogger.info(TAG, "[Bridge] materialized + verified for '$server' (expiresAt=${tokens.expiresAtMs / 1000})")
        return null
    }

    private fun redirectUri(oauth: MCPOAuthConfig): String =
        oauth.redirectUri?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REDIRECT_URI

    companion object {
        private const val TAG = "MCPOAuthController"

        private const val DISMISSAL_GRACE_MS = 1_500L

        fun watchForReturnWithoutCallback(onDismissed: () -> Unit): () -> Unit {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
                @Volatile var sawBackgrounded = false

                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { sawBackgrounded = true }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { sawBackgrounded = true }
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    if (!sawBackgrounded) return
                    sawBackgrounded = false
                    main.postDelayed({ onDismissed() }, DISMISSAL_GRACE_MS)
                }
            }
            val lifecycle = runCatching { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle }.getOrNull()
                ?: return {}
            main.post { lifecycle.addObserver(observer) }
            return { main.post { lifecycle.removeObserver(observer) } }
        }
        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        const val CALLBACK_TIMEOUT_MS = 300_000L

        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun isBridgeSafeName(server: String): Boolean =
            server.isNotBlank() && server != "." && server != ".." &&
                server.none { it == '/' || it == '\\' || it == '\u0000' }

        fun defaultBridgeDir(context: Context): File =
            File(context.filesDir, "minis-global/mcp-servers/oauth")

        internal fun buildTokenExchangeBody(
            code: String,
            redirect: String,
            clientId: String,
            verifier: String,
            clientSecret: String?,
            resource: String?,
        ): String {
            val form = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirect,
                "client_id" to clientId,
                "code_verifier" to verifier,
            )
            if (!clientSecret.isNullOrEmpty()) form["client_secret"] = clientSecret
            if (!resource.isNullOrEmpty()) form["resource"] = resource
            return formBody(form)
        }

        private fun formBody(form: Map<String, String>): String =
            form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        const val LOOPBACK_PORT = 54546
        const val DEFAULT_REDIRECT_URI = "http://localhost:$LOOPBACK_PORT/callback"
    }
}
