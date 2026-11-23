package com.openminis.app.network

import android.webkit.HttpAuthHandler
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.openminis.app.logging.AppLogger
import java.util.concurrent.Executor

object WebViewNetworkProxy {

    private const val TAG = "Network"
    private const val REALM = "DeepSeek"

    private val direct = Executor { it.run() }

    @Volatile
    private var applied: String? = null

    @Volatile
    private var credentials: Pair<String, String>? = null

    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    fun apply(snapshot: NetworkRuntime.Snapshot) {
        val ep = snapshot.endpoint(NetworkScope.BROWSER.tag)
        val hp = ep?.httpPort
        val want = hp?.let { "127.0.0.1:$it" }
        if (want == applied) {
            credentials = if (ep != null && hp != null) ep.tag to ep.password else null
            return
        }
        if (!isSupported()) {
            if (want != null) AppLogger.warning(TAG, "[Net][Browser] this WebView has no PROXY_OVERRIDE: the browser stays direct")
            return
        }
        try {
            if (want == null) {
                credentials = null
                ProxyController.getInstance().clearProxyOverride(direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> direct (override cleared)")
            } else {
                credentials = ep!!.tag to ep.password
                val config = ProxyConfig.Builder()
                    .addProxyRule("http://$want")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("[::1]")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> local entry $want (tag=${ep.tag})")
            }
            applied = want
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Net][Browser] proxy override failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun answerAuth(handler: HttpAuthHandler, host: String?, realm: String?, pageUrl: String?): Boolean {
        val c = credentials ?: return false
        if (realm != REALM) return false
        if (host != null && host != "127.0.0.1" && host != "localhost" && !host.startsWith("127.0.0.1:")) return false
        val pageHost = pageUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
        if (pageHost == "127.0.0.1" || pageHost == "localhost" || pageHost == "::1") return false
        handler.proceed(c.first, c.second)
        return true
    }
}
