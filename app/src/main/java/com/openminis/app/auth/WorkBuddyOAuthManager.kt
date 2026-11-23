package com.openminis.app.auth

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * WorkBuddy account-login manager.
 *
 * Unlike the RFC-8628 providers, WorkBuddy uses a private state/authUrl +
 * token-polling protocol (see [WorkBuddyApi]). This manager owns:
 *  - the login flow (begin -> show authUrl in a web view -> poll -> account),
 *  - persistence of the account bundle (access/refresh token, uid, domain,
 *    enterprise id, region, backend, conversation id) in encrypted prefs,
 *  - proactive token refresh backed by [WorkBuddyApi.refreshAccessToken],
 *  - [validAccessToken] for the OpenAI-compatible chat provider.
 *
 * UNOFFICIAL integration — see [WorkBuddyApi] for details and terms note.
 */
class WorkBuddyOAuthManager(
    context: Context,
    instanceId: String,
) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "WorkBuddyOAuth"
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        /** Persist a completed login and mirror the bearer to apikey_<id>. */
        fun saveAccount(context: Context, instanceId: String, account: WorkBuddyApi.Account, repo: ProviderRepository) {
            val json = JSONObject().apply {
                put("access_token", account.accessToken)
                account.refreshToken?.let { put("refresh_token", it) }
                put("uid", account.uid)
                put("nickname", account.nickname)
                put("domain", account.domain)
                put("enterprise_id", account.enterpriseId)
                put("region", account.region)
                put("backend", account.backend)
                put("conversation_id", account.conversationId)
                put("expire_at", 0L) // upstream does not return a reliable expiry; refresh lazily on 401
                put("last_refresh", System.currentTimeMillis())
            }
            // Store as the standard OAuth token bundle so base helpers work.
            // Delegate to an instance to reuse the base encrypted-prefs writer.
            WorkBuddyOAuthManager(context, instanceId).saveOAuthString("tokens", json.toString())
            repo.saveApiKey(instanceId, account.accessToken)
        }

        /** Load the persisted account, or null. */
        fun loadAccount(context: Context, instanceId: String): WorkBuddyApi.Account? {
            val mgr = WorkBuddyOAuthManager(context, instanceId)
            val json = mgr.loadStoredTokens() ?: return null
            val access = json.optString("access_token", "")
            if (access.isBlank()) return null
            return WorkBuddyApi.Account(
                accessToken = access,
                refreshToken = json.optString("refresh_token", "").ifEmpty { null },
                uid = json.optString("uid", ""),
                domain = json.optString("domain", "").ifBlank { WorkBuddyApi.DOMESTIC_DOMAIN },
                enterpriseId = json.optString("enterprise_id", ""),
                region = json.optString("region", "domestic"),
                backend = json.optString("backend", "").ifBlank { WorkBuddyApi.DOMESTIC_BACKEND },
                conversationId = json.optString("conversation_id", "").ifEmpty {
                    java.util.UUID.randomUUID().toString()
                },
                nickname = json.optString("nickname", ""),
            )
        }
    }

    // Inert base-class surface (WorkBuddy has no PKCE/callback server).
    override val authURL: String get() = WorkBuddyApi.DOMESTIC_BACKEND
    override val tokenURL: String get() = "${WorkBuddyApi.DOMESTIC_BACKEND}/v2/plugin/auth/token"
    override val clientId: String get() = "CLI"
    override val clientSecret: String? get() = null
    override val callbackPort: Int get() = 0
    override val redirectPath: String get() = ""
    override val scopes: String get() = ""

    /** Full interactive login. [onAuthUrl] must open [WorkBuddyApi.LoginSession.authUrl]
     *  in a web view; polling then waits for the user to approve there. */
    suspend fun login(
        repo: ProviderRepository,
        region: String,
        onAuthUrl: (WorkBuddyApi.LoginSession) -> Unit,
        isApproved: () -> Boolean = { true },
    ): WorkBuddyApi.Account = withContext(Dispatchers.IO) {
        val session = WorkBuddyApi.beginLogin(region)
        withContext(Dispatchers.Main) { onAuthUrl(session) }
        Log.i(TAG, "WorkBuddy login state issued; waiting for web authorization")

        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
        var delayMs = 2000L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(delayMs)
            try {
                val account = WorkBuddyApi.pollToken(session)
                if (account != null) {
                    saveAccount(context, instanceId, account, repo)
                    Log.i(TAG, "WorkBuddy login success uid=${account.uid.take(8)}")
                    return@withContext account
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll error: ${e.message}")
            }
            // back off gently, cap at 5s
            delayMs = (delayMs + 500).coerceAtMost(5000)
        }
        throw Exception("WorkBuddy 登录超时，请重试")
    }

    /** Refresh the current access token; returns the new account on success. */
    suspend fun refresh(): WorkBuddyApi.Account? = withContext(Dispatchers.IO) {
        val account = loadAccount(context, instanceId) ?: return@withContext null
        val refreshed = WorkBuddyApi.refreshAccessToken(account) ?: return@withContext null
        // Preserve the stable conversation id and mirror the new bearer.
        val withSession = refreshed.copy(conversationId = account.conversationId)
        saveAccount(context, instanceId, withSession, ProviderRepository(context.applicationContext))
        withSession
    }

    /** Bearer provider for OpenAIProvider. Refreshes on demand. */
    override suspend fun validAccessToken(): String? {
        loadManualBearerToken()?.takeIf { it.isNotEmpty() }?.let { return it }
        val account = loadAccount(context, instanceId) ?: return null
        if (account.accessToken.isNotBlank()) return account.accessToken
        return refresh()?.accessToken
    }

    /** Stable per-account conversation id, used for the channel-verification headers. */
    fun conversationId(): String =
        loadAccount(context, instanceId)?.conversationId ?: java.util.UUID.randomUUID().toString()

    fun currentAccount(): WorkBuddyApi.Account? = loadAccount(context, instanceId)
}
