package com.openminis.app.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class OAuthRedirectActivity : Activity() {

    companion object {
        private const val TAG = "OAuthRedirect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        Log.i(TAG, "OAuth redirect received: ${OAuthLogRedaction.url(uri?.toString())}")

        if (uri != null) forwardIfNeeded(uri)

        bringAppForward()
        finish()
    }

    private fun forwardIfNeeded(uri: android.net.Uri) {
        val hasCode = uri.getQueryParameter("code") != null
        val hasError = uri.getQueryParameter("error") != null
        if (!hasCode && !hasError) {
            Log.w(TAG, "No 'code' or 'error' parameter in redirect URI")
            return
        }
        val port = uri.port.takeIf { it > 0 } ?: 54545
        when (OAuthCallbackServer.forwardDecision(port, uri.getQueryParameter("state"))) {
            OAuthCallbackServer.Forward.ALREADY_SERVED -> {
                Log.i(TAG, "Server on :$port already answered this callback - not forwarding")
                return
            }
            OAuthCallbackServer.Forward.NOT_LISTENING -> {
                Log.i(TAG, "No server waiting on :$port - nothing to forward")
                return
            }
            OAuthCallbackServer.Forward.FORWARD -> Unit
        }
        Thread {
            try {
                val localUrl = "http://127.0.0.1:$port${uri.path}?${uri.query}"
                Log.d(TAG, "Forwarding to local server: ${OAuthLogRedaction.url(localUrl)}")
                val conn = java.net.URL(localUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                val responseCode = conn.responseCode
                Log.i(TAG, "Local server response: $responseCode")
                conn.disconnect()
            } catch (e: java.net.ConnectException) {
                Log.i(TAG, "Local server no longer listening on :$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward to local server: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.apply { name = com.openminis.app.diagnostics.ThreadNames.of("oauth-fwd") }.start()
    }

    private fun bringAppForward() {
        try {
            startActivity(
                Intent(this, com.openminis.app.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            Log.i(TAG, "Redirect handed to the app - bringing MainActivity forward")
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring MainActivity forward: ${e.javaClass.simpleName}")
        }
    }
}
