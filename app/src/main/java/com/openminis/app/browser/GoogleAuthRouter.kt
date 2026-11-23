package com.openminis.app.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger
import java.net.URI

object GoogleAuthRouter {
    private const val TAG = "GoogleAuthRouter"

    private val GOOGLE_AUTH_HOSTS = listOf(
        "accounts.google.com",
        "signin.google.com",
        "myaccount.google.com",
        "oauth2.googleapis.com",
        "accounts.youtube.com",
    )

    fun shouldRouteExternally(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return GOOGLE_AUTH_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun openInCustomTab(context: Context, url: String) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(context, Uri.parse(url))
            AppLogger.info(TAG, "opened in Custom Tab: $url")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Custom Tab launch failed, falling back to ACTION_VIEW: ${t.message}")
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
