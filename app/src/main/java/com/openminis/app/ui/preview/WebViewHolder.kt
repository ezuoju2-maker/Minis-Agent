package com.openminis.app.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.openminis.app.logging.AppLogger

class WebViewHolder(
    appContext: Context,
    initialUrl: String,
) {

    var pageTitle by mutableStateOf("")
        private set
    var currentUrl by mutableStateOf(initialUrl)

    var isDead by mutableStateOf(false)
        private set

    var isLoading by mutableStateOf(true)
        private set
    var desktopMode by mutableStateOf(false)
        private set
    var pageFavicon by mutableStateOf<Bitmap?>(null)
        private set

    private var mobileUserAgent: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        mobileUserAgent = settings.userAgentString
        val wv = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: android.webkit.WebView?, handler: android.webkit.HttpAuthHandler?, host: String?, realm: String?) {
                if (handler != null && com.openminis.app.network.WebViewNetworkProxy.answerAuth(handler, host, realm, view?.url)) return
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                runCatching { destroy() }
                isDead = true
                return com.openminis.app.ui.webview.WebViewRenderProcess
                    .handle("WebViewHolder", detail)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                val urlStr = request.url?.toString()
                if (com.openminis.app.browser.GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                    if (urlStr != null) {
                        com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                    }
                    return true
                }
                return com.openminis.app.ui.browser
                    .BrowserExternalSchemeHandler
                    .handle(
                        view.context,
                        request.url,
                        com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                            .Origin.USER_INITIATED,
                    )
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                isLoading = true
                currentUrl = url
                AppLogger.debug(TAG, "onPageStarted url=${url.take(160)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                isLoading = false
                pageTitle = view.title.orEmpty()
                AppLogger.debug(TAG, "onPageFinished title=${pageTitle.take(60)}")
                view.postDelayed({
                    view.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize'));",
                        null,
                    )
                }, 100)
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrEmpty()) pageTitle = title
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null) pageFavicon = icon
            }


            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                val ctx = view?.context ?: return false.also { result?.cancel() }
                if (ctx !is android.app.Activity) { result?.cancel(); return true }
                android.app.AlertDialog.Builder(ctx)
                    .setMessage(message.orEmpty())
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                val ctx = view?.context ?: return false.also { result?.cancel() }
                if (ctx !is android.app.Activity) { result?.cancel(); return true }
                android.app.AlertDialog.Builder(ctx)
                    .setMessage(message.orEmpty())
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                val ctx = view?.context ?: return false.also { result?.cancel() }
                if (ctx !is android.app.Activity) { result?.cancel(); return true }
                val input = android.widget.EditText(ctx).apply {
                    setText(defaultValue.orEmpty())
                    setSelection(text.length)
                }
                val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                val wrapper = android.widget.FrameLayout(ctx).apply {
                    setPadding(pad, pad / 2, pad, 0)
                    addView(input)
                }
                android.app.AlertDialog.Builder(ctx)
                    .setMessage(message.orEmpty())
                    .setView(wrapper)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text?.toString().orEmpty())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
        addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldH = oldBottom - oldTop
            val newH = bottom - top
            if (newH > 0 && newH != oldH) {
                (v as WebView).evaluateJavascript(
                    "window.dispatchEvent(new Event('resize'));",
                    null,
                )
            }
        }
    }

    private var hasLoaded = false

    fun startIfNeeded() {
        if (hasLoaded) return
        hasLoaded = true
        if (webView.isAttachedToWindow && webView.width > 0 && webView.height > 0) {
            AppLogger.info(TAG, "loadUrl (attached) ${currentUrl.take(160)}")
            webView.loadUrl(currentUrl)
            return
        }
        webView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.removeOnAttachStateChangeListener(this)
                v.post {
                    if (v.width > 0 && v.height > 0) {
                        AppLogger.info(TAG, "loadUrl (post-attach) ${currentUrl.take(160)}")
                        webView.loadUrl(currentUrl)
                    } else {
                        v.viewTreeObserver.addOnGlobalLayoutListener(object :
                            android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                if (v.width > 0 && v.height > 0) {
                                    v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                    AppLogger.info(TAG, "loadUrl (post-layout) ${currentUrl.take(160)}")
                                    webView.loadUrl(currentUrl)
                                }
                            }
                        })
                    }
                }
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {}
        })
    }

    fun reload() {
        AppLogger.info(TAG, "reload")
        webView.reload()
    }

    fun toggleDesktopMode() {
        desktopMode = !desktopMode
        if (desktopMode) {
            webView.settings.userAgentString = DESKTOP_UA
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.setSupportZoom(true)
            webView.settings.builtInZoomControls = true
            webView.settings.displayZoomControls = false
            applyShrinkToFit(DESKTOP_VIEWPORT_CSS_WIDTH)
        } else {
            webView.settings.userAgentString = mobileUserAgent
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
            webView.setInitialScale(0)
        }
        AppLogger.info(TAG, "toggleDesktopMode → $desktopMode")
        webView.clearCache(false)
        webView.reload()
    }

    private fun applyShrinkToFit(cssWidth: Int) {
        val containerPx = webView.width.takeIf { it > 0 }
            ?: webView.resources.displayMetrics.widthPixels
        val density = webView.resources.displayMetrics.density
        val cssWidthPx = (cssWidth * density).toInt()
        val scalePct = if (cssWidthPx > containerPx) {
            ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
        } else {
            0
        }
        webView.setInitialScale(scalePct)
    }

    fun stopLoading() {
        AppLogger.info(TAG, "stopLoading")
        webView.stopLoading()
        isLoading = false
    }

    fun detach() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
    }

    fun destroy() {
        try {
            detach()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
            AppLogger.info(TAG, "destroyed")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "destroy failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WebViewHolder"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/134.0.0.0 Safari/537.36"
        private const val DESKTOP_VIEWPORT_CSS_WIDTH = 1280
    }
}

@Composable
fun rememberWebViewHolder(url: String): WebViewHolder {
    val context = androidx.compose.ui.platform.LocalContext.current
    var holder by androidx.compose.runtime.remember(url) {
        androidx.compose.runtime.mutableStateOf(WebViewHolder(context.applicationContext, url))
    }
    if (holder.isDead) {
        holder = WebViewHolder(context.applicationContext, url)
    }
    return holder
}
