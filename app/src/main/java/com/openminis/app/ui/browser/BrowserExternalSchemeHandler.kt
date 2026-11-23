package com.openminis.app.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

object BrowserExternalSchemeHandler {
    private const val TAG = "BrowserExternalScheme"

    private val INTERNAL_SCHEMES = setOf("http", "https", "about", "file")

    private val INTENT_SCHEME = "intent"
    private val ANDROID_APP_SCHEME = "android-app"

    private val EXTERNAL_VIEW_SCHEMES = setOf(
        "tel", "mailto", "sms", "smsto", "mms", "mmsto",
        "geo", "market", "whatsapp", "tg", "weixin",
    )

    enum class Origin {
        USER_INITIATED,

        AGENT_BACKGROUND,
    }

    internal enum class Route {
        LOAD_IN_WEBVIEW,

        PARSE_URI,

        VIEW_INTENT,

        BLOCK,
    }

    internal fun route(scheme: String?, origin: Origin): Route {
        val s = scheme?.lowercase() ?: return Route.LOAD_IN_WEBVIEW
        return when {
            s in INTERNAL_SCHEMES -> Route.LOAD_IN_WEBVIEW
            s == INTENT_SCHEME || s == ANDROID_APP_SCHEME -> Route.PARSE_URI
            s in EXTERNAL_VIEW_SCHEMES -> Route.VIEW_INTENT
            origin == Origin.USER_INITIATED -> Route.VIEW_INTENT
            else -> Route.BLOCK
        }
    }

    fun shouldHandleExternally(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme !in INTERNAL_SCHEMES
    }

    fun handle(
        context: Context,
        url: String?,
        origin: Origin = Origin.USER_INITIATED,
    ): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        return handle(context, uri, origin)
    }

    fun handle(
        context: Context,
        uri: Uri?,
        origin: Origin = Origin.USER_INITIATED,
    ): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in INTERNAL_SCHEMES) return false

        return when (route(scheme, origin)) {
            Route.PARSE_URI -> handleIntentScheme(context, uri.toString())
            Route.VIEW_INTENT -> handleViewIntent(context, uri)
            Route.LOAD_IN_WEBVIEW -> false
            Route.BLOCK -> {
                AppLogger.info(TAG, "blocked unknown scheme: $scheme (uri=$uri, origin=$origin)")
                Toast.makeText(
                    context,
                    context.getString(R.string.external_link_blocked, scheme),
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }
    }

    private fun handleIntentScheme(context: Context, url: String): Boolean {
        val intent = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrElse {
            AppLogger.warning(TAG, "parseUri failed for $url: ${it.message}")
            return true
        }

        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = intent.getStringExtra("browser_fallback_url")

        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            AppLogger.info(TAG, "No app handles $url; fallback=${fallback != null}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "startActivity failed for $url: ${e.message}")
        }

        if (!fallback.isNullOrBlank()) {
            try {
                val fb = Intent(Intent.ACTION_VIEW, fallback.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fb)
                return true
            } catch (e: Exception) {
                AppLogger.warning(TAG, "fallback $fallback failed: ${e.message}")
            }
        }

        Toast.makeText(
            context,
            context.getString(R.string.external_link_no_app),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun handleViewIntent(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.external_link_no_app),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            AppLogger.warning(TAG, "ACTION_VIEW failed for $uri: ${e.message}")
        }
        return true
    }
}
