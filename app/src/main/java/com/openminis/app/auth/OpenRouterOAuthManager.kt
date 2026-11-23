package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OpenRouterOAuthManager {

    private const val TAG = "OpenRouterOAuth"
    private const val AUTH_BASE_URL = "https://openrouter.ai/auth"
    private const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
    private const val CALLBACK_PORT = 3000
    private val FALLBACK_PORTS = listOf(3001, 3002)

    private var callbackServer: OAuthCallbackServer? = null

    suspend fun login(context: Context, instanceId: String, providerRepository: ProviderRepository): String {
        Log.i(TAG, "=== OpenRouter OAuth login started (instance: $instanceId) ===")

        callbackServer?.stop()
        callbackServer = null

        val (verifier, challenge) = generatePKCE()
        Log.i(TAG, "PKCE generated — verifier length: ${verifier.length}")

        val apiKey = withContext(Dispatchers.IO) {
            val code = suspendCancellableCoroutine { cont ->
                val server = OAuthCallbackServer(
                    CALLBACK_PORT,
                    FALLBACK_PORTS,
                    onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) },
                ) { receivedCode, _ ->
                    if (cont.isActive) {
                        cont.resume(receivedCode)
                    }
                }
                callbackServer = server
                server.start()
                val boundPort = server.boundPort
                Log.i(TAG, "Callback server started on port $boundPort")

                cont.invokeOnCancellation {
                    server.stop()
                    callbackServer = null
                }

                val callbackUrl = "http://localhost:$boundPort/callback"
                val authUrl = Uri.parse(AUTH_BASE_URL).buildUpon()
                    .appendQueryParameter("callback_url", callbackUrl)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("_nc", System.currentTimeMillis().toString())
                    .build()
                Log.d(TAG, "Auth URL: ${OAuthLogRedaction.url(authUrl.toString())}")

                OAuthTabLauncher.launch(context, authUrl, newTask = true)
                Log.i(TAG, "Opened Custom Tab for OpenRouter authorization")
            }

            callbackServer?.stop()
            callbackServer = null
            Log.i(TAG, "Callback received — code length: ${code.length}")

            OAuthForegroundGate.awaitForeground(TAG)
            exchangeCode(code, verifier, com.openminis.app.network.NetworkRuntime.providerTag(instanceId))
        }

        providerRepository.saveApiKey(instanceId, apiKey)
        Log.i(TAG, "=== OpenRouter OAuth login complete (instance: $instanceId) ===")
        return apiKey
    }

    fun isAuthenticated(instanceId: String, providerRepository: ProviderRepository): Boolean {
        return providerRepository.loadApiKey(instanceId) != null
    }

    fun logout(instanceId: String, providerRepository: ProviderRepository) {
        providerRepository.deleteApiKey(instanceId)
        Log.i(TAG, "Logout — cleared API key (instance: $instanceId)")
    }

    private fun generatePKCE(): Pair<String, String> {
        val bytes = ByteArray(96)
        SecureRandom().nextBytes(bytes)
        val verifier = standardBase64ToUrlSafe(bytes)

        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        val challenge = standardBase64ToUrlSafe(hash)

        Log.d(TAG, "PKCE verifier ${OAuthLogRedaction.secret(verifier)}")
        Log.d(TAG, "PKCE challenge ${OAuthLogRedaction.secret(challenge)}")

        return verifier to challenge
    }

    private fun standardBase64ToUrlSafe(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun exchangeCode(code: String, verifier: String, netTag: String): String {
        val body = JSONObject()
        body.put("code", code)
        body.put("code_verifier", verifier)
        body.put("code_challenge_method", "S256")

        Log.d(TAG, "Exchange request: code=${OAuthLogRedaction.secret(code)} verifier=${OAuthLogRedaction.secret(verifier)}")

        val request = Request.Builder()
            .url(KEYS_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
            .header("X-Title", "Minis App")
            .build()

        val response = com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        Log.i(TAG, "Response status: ${response.code}")
        Log.d(TAG, "Response body received (len=${responseBody.length})")

        if (!response.isSuccessful) {
            throw Exception("OpenRouter key exchange failed (${response.code}): $responseBody")
        }

        val json = JSONObject(responseBody)
        return json.getString("key")
    }

}
