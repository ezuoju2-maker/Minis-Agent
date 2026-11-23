package com.openminis.app.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

class CopilotOAuthManager(
    context: Context,
    instanceId: String,
) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "CopilotOAuth"

        private const val KEY_GITHUB_TOKEN = "copilot_github_token"
        private const val KEY_SESSION_TOKEN = "copilot_session_token"
        private const val KEY_SESSION_EXPIRES = "copilot_session_expires_at"

        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        suspend fun login(
            context: Context,
            instanceId: String,
            onDeviceCode: (CopilotDeviceFlow.DeviceAuthorization) -> Unit,
        ): String {
            val mgr = CopilotOAuthManager(context, instanceId)
            val auth = mgr.requestDeviceAuthorization()
            onDeviceCode(auth)
            val githubToken = mgr.pollForToken(auth)
            mgr.saveOAuthString(KEY_GITHUB_TOKEN, githubToken)
            try {
                mgr.exchangeSessionToken(githubToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[Copilot] signed in, but the session-token exchange failed", e)
                throw Exception(
                    e.message
                        ?: "Signed in to GitHub, but could not obtain a Copilot token " +
                        "(${e.javaClass.simpleName}).",
                    e,
                )
            }
            Log.i(TAG, "Copilot login complete (instance: $instanceId)")
            return githubToken
        }
    }

    override val authURL = CopilotDeviceFlow.DEVICE_CODE_URL
    override val tokenURL = CopilotDeviceFlow.ACCESS_TOKEN_URL
    override val clientId: String get() = CopilotDeviceFlow.CLIENT_ID
    override val clientSecret: String? get() = null
    override val callbackPort: Int get() = 0
    override val redirectPath: String get() = ""
    override val scopes = CopilotDeviceFlow.SCOPE

    fun hasGithubToken(): Boolean = !loadOAuthString(KEY_GITHUB_TOKEN).isNullOrEmpty()

    override fun isAuthenticated(): Boolean = hasGithubToken()

    fun exportCopilotTokensJson(): String? {
        val github = loadOAuthString(KEY_GITHUB_TOKEN)
        if (github.isNullOrEmpty()) return null
        return JSONObject().apply {
            put("githubToken", github)
            loadOAuthString(KEY_SESSION_TOKEN)?.takeIf { it.isNotEmpty() }
                ?.let { put("sessionToken", it) }
            loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
                ?.let { put("sessionExpiresAt", it) }
        }.toString()
    }

    fun importCopilotTokensJson(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val github = obj.optString("githubToken", "")
        if (github.isEmpty()) return
        saveOAuthString(KEY_GITHUB_TOKEN, github)
        obj.optString("sessionToken", "").takeIf { it.isNotEmpty() }
            ?.let { saveOAuthString(KEY_SESSION_TOKEN, it) }
        obj.optLong("sessionExpiresAt", 0L).takeIf { it > 0 }
            ?.let { saveOAuthString(KEY_SESSION_EXPIRES, it.toString()) }
        Log.i(TAG, "[Copilot] credentials restored from backup (instance: $instanceId)")
    }


    private fun postForm(url: String, fields: Map<String, String>): Pair<Int, JSONObject> {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .post(form)
            .header("Accept", "application/json")
            .header("User-Agent", CopilotDeviceFlow.USER_AGENT)
            .build()
        val response = httpClient.newCall(request).execute()
        val code = response.code
        val body = response.body?.string().orEmpty()
        response.close()
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        return code to json
    }

    suspend fun requestDeviceAuthorization(): CopilotDeviceFlow.DeviceAuthorization =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Requesting device code (instance: $instanceId)")
            val (status, json) = postForm(
                CopilotDeviceFlow.DEVICE_CODE_URL,
                mapOf("client_id" to clientId, "scope" to CopilotDeviceFlow.SCOPE),
            )
            if (status !in 200..299) {
                val desc = json.optString("error_description", "")
                    .ifEmpty { json.optString("error", "device authorization failed") }
                Log.e(TAG, "device/code failed: $desc")
                throw Exception("GitHub device authorization failed: $desc")
            }
            CopilotDeviceFlow.parseDeviceAuthorization(json)
                ?: throw Exception("GitHub device authorization response missing required fields")
        }

    suspend fun pollForToken(auth: CopilotDeviceFlow.DeviceAuthorization): String =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
            var interval = auth.intervalSeconds
            Log.i(
                TAG,
                "[Copilot] device flow polling START interval=${interval}s " +
                    "expiresIn=${auth.expiresInSeconds}s instance=${instanceId.take(8)}",
            )
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000)
                val (status, json) = postForm(
                    CopilotDeviceFlow.ACCESS_TOKEN_URL,
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to auth.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
                val errCode = json.optString("error", "").ifEmpty {
                    if (status in 200..299) "none" else "unparsed"
                }
                Log.i(
                    TAG,
                    "[Copilot] poll status=$status error=$errCode " +
                        "keys=${json.keys().asSequence().sorted().joinToString(",")}",
                )
                when (val r = CopilotDeviceFlow.classifyPoll(json, status in 200..299)) {
                    is CopilotDeviceFlow.PollResult.Success -> {
                        Log.i(TAG, "[Copilot] device flow complete for instance ${instanceId.take(8)}")
                        return@withContext r.accessToken
                    }
                    CopilotDeviceFlow.PollResult.Pending -> Unit
                    CopilotDeviceFlow.PollResult.SlowDown -> {
                        interval = CopilotDeviceFlow.bumpedInterval(interval)
                        Log.i(TAG, "[Copilot] slow_down — interval now ${interval}s")
                    }
                    is CopilotDeviceFlow.PollResult.Denied -> {
                        Log.e(TAG, "[Copilot] device flow DENIED: ${r.description}")
                        throw Exception("GitHub sign-in was denied: ${r.description}")
                    }
                    is CopilotDeviceFlow.PollResult.Expired -> {
                        Log.e(TAG, "[Copilot] device flow EXPIRED")
                        throw Exception("The device code expired — please try again.")
                    }
                    is CopilotDeviceFlow.PollResult.Fatal -> {
                        Log.e(TAG, "[Copilot] device flow FATAL: ${r.description}")
                        throw Exception("GitHub sign-in failed: ${r.description}")
                    }
                }
            }
            Log.e(
                TAG,
                "[Copilot] device flow timed out after ${auth.expiresInSeconds}s " +
                    "without a terminal answer",
            )
            throw Exception("The device code expired — please try again.")
        }


    private suspend fun exchangeSessionToken(githubToken: String): String =
        withContext(Dispatchers.IO) { exchangeSessionTokenBlocking(githubToken) }

    private fun exchangeSessionTokenBlocking(githubToken: String): String {
        val builder = Request.Builder().url(CopilotDeviceFlow.COPILOT_TOKEN_URL).get()
        CopilotDeviceFlow.tokenExchangeHeaders(githubToken).forEach { (k, v) ->
            builder.header(k, v)
        }
        val response = httpClient.newCall(builder.build()).execute()
        val status = response.code
        val body = response.body?.string().orEmpty()
        response.close()
        if (status !in 200..299) {
            Log.e(TAG, "Copilot token exchange failed: HTTP $status")
            throw Exception(
                if (status == 401 || status == 403) {
                    "This GitHub account does not have Copilot access (HTTP $status)."
                } else {
                    "Copilot token exchange failed (HTTP $status)."
                }
            )
        }
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis() / 1000
        val session = CopilotDeviceFlow.parseSessionToken(json, now)
            ?: throw Exception("Copilot token exchange returned no usable token.")
        saveOAuthString(KEY_SESSION_TOKEN, session.token)
        saveOAuthString(KEY_SESSION_EXPIRES, session.expiresAtEpochSeconds.toString())
        Log.i(TAG, "Copilot session token refreshed (expires_at=${session.expiresAtEpochSeconds})")
        return session.token
    }

    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val githubToken = loadOAuthString(KEY_GITHUB_TOKEN)
        if (githubToken.isNullOrEmpty()) return@withContext null

        val cached = loadOAuthString(KEY_SESSION_TOKEN)
        val expiresAt = loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
        val now = System.currentTimeMillis() / 1000
        if (!cached.isNullOrEmpty() && expiresAt != null &&
            !CopilotDeviceFlow.SessionToken(cached, expiresAt).needsRefresh(now)
        ) {
            return@withContext cached
        }

        mutexFor(instanceId).withLock {
            val freshExpiry = loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
            val freshToken = loadOAuthString(KEY_SESSION_TOKEN)
            val nowInner = System.currentTimeMillis() / 1000
            if (!freshToken.isNullOrEmpty() && freshExpiry != null &&
                !CopilotDeviceFlow.SessionToken(freshToken, freshExpiry).needsRefresh(nowInner)
            ) {
                return@withLock freshToken
            }
            runCatching { exchangeSessionToken(githubToken) }
                .onFailure { Log.e(TAG, "Session refresh failed: ${it.message}") }
                .getOrNull()
        }
    }

    fun logoutCopilot() {
        saveOAuthString(KEY_GITHUB_TOKEN, "")
        saveOAuthString(KEY_SESSION_TOKEN, "")
        saveOAuthString(KEY_SESSION_EXPIRES, "")
        logout()
        Log.i(TAG, "Copilot credentials cleared (instance: $instanceId)")
    }


    suspend fun fetchModels(): List<Pair<String, String>> =
        fetchModelsDetailed().map { it.id to it.displayName }

    suspend fun fetchModelsDetailed(): List<CopilotDeviceFlow.CopilotModel> =
        withContext(Dispatchers.IO) {
            val session = validAccessToken()
            if (session == null) {
                Log.w(TAG, "[Copilot] /models skipped — no valid session token")
                return@withContext emptyList()
            }
            val builder = Request.Builder()
                .url("${CopilotDeviceFlow.API_BASE}/models")
                .get()
                .header("Authorization", "Bearer $session")
            CopilotDeviceFlow.staticChatHeaders().forEach { (k, v) -> builder.header(k, v) }
            builder.header("X-Request-Id", java.util.UUID.randomUUID().toString())

            val result = runCatching {
                val response = httpClient.newCall(builder.build()).execute()
                val ok = response.isSuccessful
                val code = response.code
                val body = response.body?.string().orEmpty()
                response.close()
                if (!ok) {
                    Log.e(TAG, "[Copilot] /models returned HTTP $code")
                    return@runCatching emptyList<CopilotDeviceFlow.CopilotModel>()
                }
                val parsed = CopilotDeviceFlow.parseModelsDetailed(JSONObject(body))
                Log.i(
                    TAG,
                    "[Copilot] ${parsed.models.size} selectable models of " +
                        "${parsed.totalReturned} returned; " +
                        "ids=[${parsed.models.joinToString(",") { it.id }}]",
                )
                if (parsed.hiddenByPolicy.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "[Copilot] hidden by model_picker_enabled=false: " +
                            parsed.hiddenByPolicy.joinToString(","),
                    )
                }
                if (parsed.droppedByType.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "[Copilot] dropped by capabilities.type != chat: " +
                            parsed.droppedByType.joinToString(","),
                    )
                }
                if (parsed.models.isEmpty()) {
                    Log.e(
                        TAG,
                        "[Copilot] model list is EMPTY after filtering — " +
                            "${parsed.totalReturned} entries returned by /models",
                    )
                }
                parsed.models
            }
            result.onFailure { Log.e(TAG, "[Copilot] /models fetch failed: ${it.message}") }
            result.getOrDefault(emptyList())
        }
}
