package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AntigravityOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "AntigravityOAuth"
        private const val USER_AGENT = "antigravity/1.107.0 android/aarch64"

        val cloudCodeBaseURLs = listOf(
            "https://daily-cloudcode-pa.sandbox.googleapis.com",
            "https://cloudcode-pa.googleapis.com",
        )
    }

    override val authURL = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenURL = "https://oauth2.googleapis.com/token"
    override val clientId = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    override val clientSecret = "GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxxxxx"
    override val callbackPort = 8086
    override val redirectPath = "/oauth2callback"
    override val scopes = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    ).joinToString(" ")

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

    var activeBaseURL: String?
        get() = loadOAuthString("base_url")
        private set(value) { value?.let { saveOAuthString("base_url", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        fetchUserEmail()
        discoverProjectIfNeeded()
    }

    private suspend fun fetchUserEmail() = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: return@withContext
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
        if (!gcpProjectId.isNullOrEmpty() && !activeBaseURL.isNullOrEmpty()) return@withContext true
        val token = validAccessToken() ?: return@withContext false

        for (baseURL in cloudCodeBaseURLs) {
            val projectId = loadCodeAssist(token, baseURL)
            if (!projectId.isNullOrEmpty()) {
                gcpProjectId = projectId
                activeBaseURL = baseURL
                Log.i(TAG, "Antigravity project discovered via $baseURL")
                return@withContext true
            }
        }
        Log.w(TAG, "Antigravity project discovery failed on all base URLs")
        false
    }

    private fun loadCodeAssist(token: String, baseURL: String): String? {
        return try {
            val req = Request.Builder()
                .url("$baseURL/v1internal:loadCodeAssist")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .header("X-Client-Name", "antigravity")
                .header("X-Client-Version", "1.107.0")
                .build()
            val response = httpClient.newCall(req).execute()
            val body = if (response.isSuccessful) response.body?.string() else null
            response.close()
            body?.let { JSONObject(it).optString("gcpProjectId").ifEmpty { null } }
        } catch (e: Exception) {
            Log.d(TAG, "loadCodeAssist failed on $baseURL: ${e.message}")
            null
        }
    }
}
