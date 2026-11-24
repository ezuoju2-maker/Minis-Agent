package com.openminis.app.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

class BrowserUseManager(
    val webView: WebView,
    profile: UserAgentProfile = UserAgentProfile.MOBILE_CHROME,
    private val sessionIdProvider: () -> String? = { null },
    private val appContext: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "BrowserUseManager"
        private const val NAVIGATION_TIMEOUT_MS = 30_000L
        private const val SCREENSHOT_QUALITY = 80
        private const val SNAPSHOT_QUALITY = 70
        private const val DEFAULT_DOM_STABLE_TIMEOUT_MS = 5_000

        private const val EXECUTE_JS_DID_NOT_PARSE = "\u0000minis:execute_js-did-not-parse"

        private const val MAX_FULL_PAGE_HEIGHT_PX = 32768

        private const val SCROLL_PROBE_TIMEOUT_MS = 500L

        private const val FULL_PAGE_REPAINT_DELAY_MS = 120L

        private const val BLANK_PAGE_HTML =
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"
        private const val MIN_DOM_STABLE_TIMEOUT_MS = 1_000
        private const val MAX_DOM_STABLE_TIMEOUT_MS = 60_000

        @SuppressLint("SetJavaScriptEnabled")
        fun configureWebView(webView: WebView, profile: UserAgentProfile, customUA: String? = null) {
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                val ua = customUA ?: profile.userAgentString
                if (ua != null) userAgentString = ua
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    private val _currentURL = MutableStateFlow("")
    val currentURL: StateFlow<String> = _currentURL.asStateFlow()

    private val dialogQueue = InterceptedDialogQueue()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private val _profile = MutableStateFlow(profile)
    val profileFlow: StateFlow<UserAgentProfile> = _profile.asStateFlow()
    private var currentProfile: UserAgentProfile
        get() = _profile.value
        set(value) { _profile.value = value }

    var viewportOverride: () -> Pair<Int, Int>? = { null }

    var onNewWindow: ((Message) -> Unit)? = null

    var onCloseWindow: (() -> Unit)? = null

    var onDownloadStart: ((url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) -> Unit)? = null

    var onBlobDownloadData: ((data: ByteArray, filename: String, mimeType: String?) -> Unit)? = null

    private var navigationDeferred: CompletableDeferred<Unit>? = null

    private val screenshotsDir: File by lazy {
        File(webView.context.cacheDir, "browser_screenshots").also { it.mkdirs() }
    }

    private var asyncJsDeferred: CompletableDeferred<String>? = null

    private val jsBridge = object {
        private val armed = java.util.concurrent.atomic.AtomicBoolean(false)

        fun arm() { armed.set(true) }
        fun disarm() { armed.set(false) }

        @JavascriptInterface
        fun resolve(result: String) {
            if (!armed.compareAndSet(true, false)) {
                Log.w(TAG, "ignored unsolicited __minis__.resolve")
                return
            }
            asyncJsDeferred?.complete(result)
            asyncJsDeferred = null
        }

        @JavascriptInterface
        fun reject(error: String) {
            if (!armed.compareAndSet(true, false)) {
                Log.w(TAG, "ignored unsolicited __minis__.reject")
                return
            }
            asyncJsDeferred?.complete("{\"error\":${JSONObject.quote(error)}}")
            asyncJsDeferred = null
        }

        @JavascriptInterface
        fun saveBlobDownload(dataUrl: String, filename: String) {
            val safeName = sanitizeDownloadName(filename)
            if (safeName.isEmpty()) {
                Log.w(TAG, "blob download: rejected unsafe filename")
                return
            }
            if (dataUrl.length > MAX_BLOB_DATA_URL_CHARS) {
                Log.w(TAG, "blob download: payload too large (${dataUrl.length} chars)")
                return
            }
            val comma = dataUrl.indexOf(',')
            if (comma < 0 || !dataUrl.startsWith("data:")) {
                Log.w(TAG, "blob download: malformed data URL (len=${dataUrl.length})")
                return
            }
            val header = dataUrl.substring(5, comma)
            val mime = header.substringBefore(';').ifEmpty { null }
            val bytes = try {
                if (header.endsWith(";base64")) {
                    android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
                } else {
                    java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8")
                        .toByteArray(Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "blob download: payload decode failed: ${t.message}")
                return
            }
            Log.i(TAG, "blob download decoded: $safeName (${bytes.size} bytes, mime=$mime)")
            onBlobDownloadData?.invoke(bytes, safeName, mime)
        }

        @JavascriptInterface
        fun blobDownloadError(error: String) {
            Log.w(TAG, "blob download failed in page JS: $error")
        }

        private fun sanitizeDownloadName(raw: String): String {
            val name = raw.substringAfterLast('/').substringAfterLast('\\')
                .trim()
                .filter { it.code in 32..126 && it != '/' && it != '\\' }
            if (name.isEmpty() || name == "." || name == "..") return ""
            return name.take(120)
        }

        private val MAX_BLOB_DATA_URL_CHARS = 32 * 1024 * 1024
    }

    init {
        configureWebView(webView, profile)
        webView.addJavascriptInterface(jsBridge, "__minis__")
        setupWebViewClient()
        setupWebChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "onDownloadStart: ${url.take(120)} mime=$mimetype len=$contentLength")
            when {
                url.startsWith("blob:") -> fetchBlobDownload(url, contentDisposition, mimetype)
                url.startsWith("data:") -> {
                    val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                    jsBridge.saveBlobDownload(url, name)
                }
                else -> onDownloadStart?.invoke(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }
        webView.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != lastKnownContainerWidthPx) {
                lastKnownContainerWidthPx = w
                lastAppliedViewport?.let { (vw, _) -> applyShrinkToFit(vw) }
            }
        }
    }

    private fun fetchBlobDownload(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        val guessedName = android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        val js = """
            (function() {
                fetch(${JSONObject.quote(blobUrl)})
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            __minis__.saveBlobDownload(reader.result, ${JSONObject.quote(guessedName)});
                        };
                        reader.onerror = function() { __minis__.blobDownloadError('FileReader error'); };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(e) { __minis__.blobDownloadError(String(e)); });
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private var lastKnownContainerWidthPx: Int = 0

    private fun applyShrinkToFit(cssWidth: Int) {
        val containerPx = lastKnownContainerWidthPx
        if (containerPx <= 0 || cssWidth <= 0) return
        val density = webView.resources.displayMetrics.density
        val cssWidthPx = (cssWidth * density).toInt()
        var scalePct = if (cssWidthPx > containerPx) {
            ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
        } else {
            0
        }
        if (scalePct in 1..9) {
            Log.w(TAG, "applyShrinkToFit: implausible scale $scalePct% (container=${containerPx}px, css=${cssWidthPx}px) — using default")
            scalePct = 0
        }
        webView.setInitialScale(scalePct)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Volatile
    var isRendererDead: Boolean = false
        private set

    @Volatile
    var isWedged: Boolean = false
        private set

    @Volatile
    var wedgeReason: String? = null
        private set

    val isUnusable: Boolean get() = isRendererDead || isWedged

    fun markWedged(reason: String) {
        if (isWedged) return
        wedgeReason = reason
        isWedged = true
        Log.w(TAG, "Tab marked unresponsive: $reason")
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: android.webkit.WebView?, handler: android.webkit.HttpAuthHandler?, host: String?, realm: String?) {
                if (handler != null && com.openminis.app.network.WebViewNetworkProxy.answerAuth(handler, host, realm, view?.url)) return
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                isRendererDead = true
                return com.openminis.app.ui.webview.WebViewRenderProcess
                    .handle("BrowserUseManager", detail)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val urlStr = request.url?.toString()
                if (GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                    if (urlStr != null) {
                        GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                    }
                    return true
                }
                return com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                    .handle(
                        view.context,
                        request.url,
                        com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                            .Origin.AGENT_BACKGROUND,
                    )
            }

            override fun onPageFinished(view: WebView, url: String?) {
                _isLoading.value = false
                _currentURL.value = url ?: ""
                _pageTitle.value = view.title ?: ""
                _canGoBack.value = view.canGoBack()
                _canGoForward.value = view.canGoForward()
                navigationDeferred?.complete(Unit)
                navigationDeferred = null
                val histUrl = url ?: ""
                val histTitle = view.title ?: ""
                if (histUrl.isNotEmpty() && histUrl != "about:blank") {
                    BrowserHistoryStore.getInstance(view.context).record(histUrl, histTitle)
                }
                view.postDelayed({
                    view.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize'));", null,
                    )
                }, 80)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    _isLoading.value = false
                    Log.e(TAG, "Navigation error: ${error.description}")
                    navigationDeferred?.complete(Unit)
                    navigationDeferred = null
                }
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val url = request.url ?: return null
                if (url.scheme != "minis") return null
                return interceptMinisURL(url)
            }
        }
    }

    private fun interceptMinisURL(uri: android.net.Uri): android.webkit.WebResourceResponse? {
        try {
            val host = uri.host ?: return null
            val path = uri.path ?: ""
            val linuxPath = "/var/minis/$host$path"

            val sessionId = sessionIdProvider()
            val ctx = appContext
            val localFile = if (sessionId != null && ctx != null) {
                com.openminis.app.sandbox.PRootKernel
                    .resolveSessionHostPath(sessionId, linuxPath, ctx)
                    ?.takeIf { it.isFile }
                    ?: com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
            } else {
                com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
            }
            if (localFile == null || !localFile.exists() || !localFile.isFile) {
                return android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    emptyMap(), "File not found: $host$path".byteInputStream())
            }
            val mimeType = guessMimeType(localFile.name)
            val stream = if (mimeType == "text/html" && lastAppliedViewport != null) {
                ensureMetaViewport(localFile.readBytes(), lastAppliedViewport!!.first)
            } else {
                localFile.inputStream()
            }
            return android.webkit.WebResourceResponse(mimeType, "UTF-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                stream)
        } catch (e: Exception) {
            Log.w(TAG, "minis:// intercept error: ${e.message}")
            return null
        }
    }

    private fun ensureMetaViewport(html: ByteArray, cssWidth: Int): java.io.InputStream {
        val text = String(html, Charsets.UTF_8)
        if (text.contains("name=\"viewport\"", ignoreCase = true) ||
            text.contains("name='viewport'", ignoreCase = true)) {
            return text.toByteArray(Charsets.UTF_8).inputStream()
        }
        val meta = "<meta name=\"viewport\" content=\"width=$cssWidth, initial-scale=1.0, user-scalable=yes\">"
        val headIdx = text.indexOf("<head", ignoreCase = true).takeIf { it >= 0 }?.let {
            text.indexOf('>', it).takeIf { gt -> gt >= 0 }?.plus(1)
        }
        val rewritten = if (headIdx != null) {
            text.substring(0, headIdx) + meta + text.substring(headIdx)
        } else {
            "$meta$text"
        }
        return rewritten.toByteArray(Charsets.UTF_8).inputStream()
    }

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "xml" -> "text/xml"
            else -> "application/octet-stream"
        }
    }

    private fun setupWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                _pageTitle.value = title ?: ""
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                onNewWindow?.invoke(resultMsg)
                return onNewWindow != null
            }

            override fun onCloseWindow(window: WebView) {
                onCloseWindow?.invoke()
            }


            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "alert",
                    message = message.orEmpty(),
                    defaultText = null,
                    defaultResponse = "(dismissed)",
                )
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "confirm",
                    message = message.orEmpty(),
                    defaultText = null,
                    defaultResponse = "false",
                )
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "prompt",
                    message = message.orEmpty(),
                    defaultText = defaultValue,
                    defaultResponse = "null",
                )
                result?.cancel()
                return true
            }
        }
    }

    private fun recordInterceptedDialog(
        kind: String,
        message: String,
        defaultText: String?,
        defaultResponse: String,
    ) {
        val url = _currentURL.value
        dialogQueue.record(
            kind = kind,
            message = message,
            defaultText = defaultText,
            pageURL = url.ifEmpty { null },
            defaultResponse = defaultResponse,
        )
        Log.i(TAG, "[JSDialog] intercepted $kind on $url — answered $defaultResponse")
    }

    fun drainInterceptedDialogReport(): String? = dialogQueue.drainReport()


    suspend fun execute(input: BrowserActionInput): BrowserActionResult {
        if (isUnusable) {
            throw WebViewWedgedException(
                if (isRendererDead) "browser tab renderer process died"
                else "browser tab stopped responding (${wedgeReason ?: "unknown"})",
            )
        }
        val prevUrl = withContext(Dispatchers.Main) { webView.url }
        var result: BrowserActionResult = when (input.action) {
            BrowserAction.NAVIGATE -> navigate(input.url)
            BrowserAction.SCREENSHOT -> return screenshot(fullPage = input.fullPage)
            BrowserAction.CLICK -> click(input.selector, input.coordinateX, input.coordinateY)
            BrowserAction.TYPE -> type(input.selector, input.text)
            BrowserAction.GET_TEXT -> return getText(input.selector)
            BrowserAction.SCROLL -> scroll(input.selector, input.direction, input.amount)
            BrowserAction.GET_PAGE_INFO -> return getPageInfo()
            BrowserAction.EXECUTE_JS -> return executeJS(input.script)
            BrowserAction.FIND_ELEMENTS -> return findElements(input.selector)
            BrowserAction.HOVER -> hover(input.selector)
            BrowserAction.GET_READABLE -> return getReadable()
            BrowserAction.SET_USER_AGENT -> return setUserAgent(input.userAgent, input.userAgentRaw)
            BrowserAction.SET_VIEWPORT ->
                return BrowserActionResult.error("set_viewport must be routed through BrowserTabPool")
            BrowserAction.GET_BACKBONE -> return getBackbone(input.maxDepth)
            BrowserAction.FETCH -> return fetch(input.url)
            BrowserAction.GET_COOKIES -> return getCookies(input.keywords, input.fuzzy)
            BrowserAction.SET_COOKIES -> return setCookies(input.cookies)
            BrowserAction.SCROLL_AND_COLLECT -> return scrollAndCollect(
                input.scrollCount, input.itemSelector, input.keywords,
            )
            BrowserAction.WAIT_FOR_DOM_STABLE -> return waitForDomStable(input.timeoutMs)
            BrowserAction.NEW_TAB, BrowserAction.CLOSE_TAB, BrowserAction.LIST_TABS ->
                return BrowserActionResult.error("Tab management actions must be routed through BrowserTabPool")
        }

        if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
            result = attachSnapshot(result)
        }

        if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
            val newUrl = withContext(Dispatchers.Main) { webView.url }
            if (prevUrl != null && newUrl != null) {
                val prevNoHash = prevUrl.substringBefore("#")
                val curNoHash = newUrl.substringBefore("#")
                if (prevNoHash != curNoHash) {
                    result = result.copy(
                        text = result.text + "\n[URL Changed] Page navigated: $prevUrl -> $newUrl. Take a screenshot to see the current state before continuing."
                    )
                }
            }
        }

        return result
    }

    private suspend fun attachSnapshot(result: BrowserActionResult): BrowserActionResult {
        return try {
            delay(300)
            val bitmap = captureWebViewBitmap(gpu = true) ?: return result
            val file = saveBitmapToFile(bitmap, "snapshot", SNAPSHOT_QUALITY)
            bitmap.recycle()
            result.copy(imageFilePath = file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Auto-snapshot failed: ${e.message}")
            result
        }
    }


    private suspend fun navigate(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("Missing 'url' parameter")

        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://$normalized"

        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true

        withContext(Dispatchers.Main) {
            lastAppliedViewport?.let { (w, h) -> applyViewport(w, h) }
            webView.loadUrl(normalized)
        }

        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                Log.w(TAG, "Navigation timed out for $normalized")
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)

        try {
            deferred.await()
        } finally {
            handler.removeCallbacks(timeoutRunnable)
        }

        _currentURL.value = _currentURL.value.ifEmpty { normalized }
        _isLoading.value = false

        val meta = navigationMetadata()
        return BrowserActionResult(text = meta)
    }

    private suspend fun navigationMetadata(): String {
        val url = _currentURL.value
        val title = _pageTitle.value
        val scrollInfo = try {
            evaluateJavascript(
                "JSON.stringify({" +
                    "sx:window.scrollX||0,sy:window.scrollY||0," +
                    "pw:document.documentElement.scrollWidth||0," +
                    "ph:document.documentElement.scrollHeight||0," +
                    "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                    "})"
            )
        } catch (e: WebViewWedgedException) {
            return buildString {
                appendLine("Navigated to $url")
                if (title.isNotEmpty()) appendLine("  Title: $title")
                append("  (page metrics unavailable: ${e.message})")
            }
        }
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            appendLine("Navigated to $url")
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }


    // -- Screenshot --
    private suspend fun screenshot(fullPage: Boolean = false): BrowserActionResult {
        var truncated = false
        var originalHeightPx = 0
        var didStretch = false
        var savedW = 0
        var savedH = 0

        if (fullPage) {
            val cssScrollHeight = evaluateJavascript("document.documentElement.scrollHeight").let {
                it.trim().toIntOrNull() ?: 0
            }
            val density = webView.resources.displayMetrics.density
            val scrollHeightPx = if (cssScrollHeight > 0) {
                (cssScrollHeight * density).toInt()
            } else {
                withContext(Dispatchers.Main) { webView.height }
            }
            originalHeightPx = scrollHeightPx
            val cappedPx = scrollHeightPx.coerceAtMost(MAX_FULL_PAGE_HEIGHT_PX)
            truncated = scrollHeightPx > MAX_FULL_PAGE_HEIGHT_PX

            try {
                evaluateJavascript(
                    """
                    (async () => {
                        document.querySelectorAll('img[loading="lazy"]').forEach(i => i.loading = 'eager');
                        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                        return 'ok';
                    })()
                    """.trimIndent()
                )
            } catch (_: Exception) {  }
            delay(50)

            val applied = lastAppliedViewport ?: currentProfile.viewportSize
            savedW = applied.first
            savedH = applied.second
            val cssCappedHeight = (cappedPx / density).toInt().coerceAtLeast(savedH)
            withContext(Dispatchers.Main) {
                applyViewport(savedW, cssCappedHeight)
            }
            didStretch = true
            delay(FULL_PAGE_REPAINT_DELAY_MS)
            Log.i(TAG, "full_page stretch: ${savedW}x$cssCappedHeight CSS (px=$cappedPx, original=$scrollHeightPx, truncated=$truncated)")
        }

        val bitmap = try {
            captureWebViewBitmap(gpu = !didStretch)
        } finally {
            if (didStretch) {
                withContext(Dispatchers.Main) {
                    applyViewport(savedW, savedH)
                }
            }
        } ?: return BrowserActionResult.error("Failed to capture screenshot")

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, out)
        val jpegBytes = out.toByteArray()

        val file = saveBitmapToFile(bitmap, "screenshot")
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val w = bitmap.width; val h = bitmap.height
        bitmap.recycle()

        Log.i(TAG, "Screenshot saved: ${file.absolutePath}, ${w}x$h, ${jpegBytes.size} bytes (full_page=$fullPage)")

        val meta = viewportMetadata(
            imageW = w,
            imageH = h,
            fileSize = jpegBytes.size,
            fullPage = fullPage,
            truncated = truncated,
            originalHeightPx = originalHeightPx,
        )

        return BrowserActionResult(
            text = meta, base64Image = base64, imageFilePath = file.absolutePath
        )
    }

    private suspend fun viewportMetadata(
        imageW: Int,
        imageH: Int,
        fileSize: Int,
        fullPage: Boolean = false,
        truncated: Boolean = false,
        originalHeightPx: Int = 0,
    ): String {
        val url = _currentURL.value
        val title = _pageTitle.value

        val scrollInfo = evaluateJavascript(
            "JSON.stringify({" +
                "sx:window.scrollX||0,sy:window.scrollY||0," +
                "pw:document.documentElement.scrollWidth||0," +
                "ph:document.documentElement.scrollHeight||0," +
                "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                "})"
        )
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            appendLine("Screenshot captured")
            appendLine("  URL: $url")
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Image: ${imageW}x$imageH (${fileSize / 1024}KB)")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            if (fullPage) {
                appendLine("  Full page: true")
                if (originalHeightPx > 0) appendLine("  Original height: ${originalHeightPx}px")
                if (truncated) appendLine("  Truncated: true (capped at ${MAX_FULL_PAGE_HEIGHT_PX}px)")
            }
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }

    suspend fun captureLiveSnapshot(): Bitmap? = captureWebViewBitmap()

    private suspend fun readCssScrollOffset(): Pair<Float, Float> {
        val raw = withTimeoutOrNull(SCROLL_PROBE_TIMEOUT_MS) {
            val d = CompletableDeferred<String>()
            webView.evaluateJavascript(
                "(function(){return (window.scrollX||window.pageXOffset||0)+','+" +
                    "(window.scrollY||window.pageYOffset||0);})()",
            ) { v -> if (!d.isCompleted) d.complete(v ?: "") }
            d.await()
        } ?: return 0f to 0f
        val cleaned = raw.trim().removeSurrounding("\"")
        val parts = cleaned.split(',')
        if (parts.size != 2) return 0f to 0f
        val x = parts[0].toFloatOrNull() ?: 0f
        val y = parts[1].toFloatOrNull() ?: 0f
        return x to y
    }

    private suspend fun captureWebViewBitmap(gpu: Boolean = false): Bitmap? = withContext(Dispatchers.Main) {
        try {
            val vp = currentProfile.viewportSize
            val density = webView.resources.displayMetrics.density
            var w = webView.width
            var h = webView.height
            if (w <= 0 || h <= 0) {
                val targetW = (vp.first * density).toInt().coerceAtLeast(1)
                val targetH = (vp.second * density).toInt().coerceAtLeast(1)
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(targetW, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(targetH, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, targetW, targetH)
                w = targetW; h = targetH
            }
            if (gpu) HeadlessRenderHost.capture(webView, w, h)?.let { return@withContext it }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val cssScroll = readCssScrollOffset()
            val offsetX = if (webView.scrollX != 0) webView.scrollX.toFloat() else cssScroll.first * density
            val offsetY = if (webView.scrollY != 0) webView.scrollY.toFloat() else cssScroll.second * density
            if (offsetX != 0f || offsetY != 0f) canvas.translate(-offsetX, -offsetY)
            webView.draw(canvas)
            Log.d(TAG, "captureWebViewBitmap ${w}x$h offset=(${offsetX},${offsetY}) " +
                "css=(${cssScroll.first},${cssScroll.second}) view=(${webView.scrollX},${webView.scrollY})")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "captureWebViewBitmap failed: ${e.message}")
            null
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, prefix: String, quality: Int = SCREENSHOT_QUALITY): File {
        val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
        val file = File(screenshotsDir, filename)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }


    private suspend fun click(selector: String?, x: Int?, y: Int?): BrowserActionResult {
        val js = when {
            selector != null -> BrowserUseJS.click(selector)
            x != null && y != null -> BrowserUseJS.clickCoordinate(x, y)
            else -> return BrowserActionResult.error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
        }
        return evaluateAndReturn(js)
    }


    private suspend fun type(selector: String?, text: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("type requires 'selector'")
        if (text == null) return BrowserActionResult.error("type requires 'text'")
        return evaluateAndReturn(BrowserUseJS.type(selector, text))
    }


    private suspend fun getText(selector: String?): BrowserActionResult {
        val js = BrowserUseJS.getText(selector)
        return evaluateJSAndParse(js)
    }


    private suspend fun getReadable(): BrowserActionResult {
        return evaluateJSAndParse(BrowserUseJS.getReadable())
    }


    private suspend fun scroll(selector: String?, direction: ScrollDirection?, amount: Int?): BrowserActionResult {
        val dir = direction ?: ScrollDirection.DOWN
        val px = amount ?: 500
        val js = BrowserUseJS.scroll(dir, px, selector)
        return evaluateJSAndParse(js)
    }


    private suspend fun getPageInfo(): BrowserActionResult {
        return evaluateAndReturn(BrowserUseJS.getPageInfo())
    }


    private suspend fun executeJS(script: String?): BrowserActionResult {
        if (script.isNullOrEmpty()) return BrowserActionResult.error("execute_js requires 'script'")
        return try {
            val deferred = CompletableDeferred<String>()
            asyncJsDeferred = deferred
            jsBridge.arm()
            val wrapped = """
                (async function(){
                    try {
                        var __r__ = (async function(){ $script })();
                        var __v__ = await __r__;
                        if (__v__ === undefined || __v__ === null) {
                            __minis__.resolve(String(__v__));
                        } else if (typeof __v__ === 'object') {
                            __minis__.resolve(JSON.stringify(__v__));
                        } else {
                            __minis__.resolve(String(__v__));
                        }
                    } catch(e) {
                        __minis__.reject(${BrowserUseJS.SCRIPT_THREW_JS});
                    }
                })();
            """.trimIndent()
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(wrapped) { value ->
                    if (BrowserJSResult.decode(value) == null) {
                        jsBridge.disarm()
                        deferred.complete(EXECUTE_JS_DID_NOT_PARSE)
                    }
                }
            }
            val raw = withTimeoutOrNull(30_000L) { deferred.await() }
                ?: run {
                    asyncJsDeferred = null
                    jsBridge.disarm()
                    return BrowserActionResult.error("JavaScript execution timed out (30s)")
                }
            asyncJsDeferred = null
            jsBridge.disarm()
            if (raw == EXECUTE_JS_DID_NOT_PARSE) {
                val detail = try {
                    evaluateJavascriptValue(BrowserUseJS.syntaxCheck(script))
                } catch (_: Exception) {
                    null
                }
                return BrowserActionResult.error(BrowserJSResult.syntaxErrorMessage(detail))
            }
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(text = formatJSONResult(json))
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: Exception) {
            asyncJsDeferred = null
            jsBridge.disarm()
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }


    // -- Find Elements --
    private suspend fun findElements(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("find_elements requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.findElements(selector))
    }


    private suspend fun hover(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("hover requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.hover(selector))
    }


    // -- Get Backbone --
    private suspend fun getBackbone(maxDepth: Int?): BrowserActionResult {
        val depth = maxDepth ?: 5
        return evaluateActionScript(BrowserUseJS.getBackbone(depth)) { BrowserActionResult(text = formatBackboneResult(it)) }
    }


    // -- Fetch --
    private suspend fun fetch(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("fetch requires 'url' parameter")

        val raw = awaitPromiseJs(BrowserUseJS.fetch(urlString))
            ?: return BrowserActionResult.error("fetch timed out")
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                return BrowserActionResult.error("Fetch failed: ${json.getString("error")}")
            }
            val contentType = json.optString("contentType", "")
            val status = json.optInt("status", 0)
            val finalURL = json.optString("url", urlString)

            val data: ByteArray? = json.optString("base64").takeIf { it.isNotEmpty() }?.let {
                try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
            } ?: json.optString("text").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

            if (data == null) {
                return BrowserActionResult.error("Fetch returned no body (status=$status)")
            }
            val size = json.optInt("size", data.size)
            val filename = "fetch_${System.currentTimeMillis()}.${extensionForMimeType(contentType)}"

            val text = buildString {
                appendLine("Fetched $finalURL")
                appendLine("  Status: $status")
                appendLine("  Content-Type: $contentType")
                appendLine("  Size: ${formatBytes(size)}")
                append("  Filename: $filename")
            }

            BrowserActionResult(
                text = text,
                fetchedFileData = data,
                fetchedFileName = filename,
            )
        } catch (e: Exception) {
            BrowserActionResult.error("Fetch parse error: ${e.message}")
        }
    }

    private suspend fun awaitPromiseJs(js: String): String? {
        val deferred = CompletableDeferred<String>()
        asyncJsDeferred = deferred
        jsBridge.arm()
        val wrapped = """
            (async function(){
                try {
                    var __v__ = await ($js);
                    if (__v__ === undefined || __v__ === null) {
                        __minis__.resolve('null');
                    } else if (typeof __v__ === 'object') {
                        __minis__.resolve(JSON.stringify(__v__));
                    } else {
                        __minis__.resolve(String(__v__));
                    }
                } catch(e) {
                    __minis__.reject(e && e.message ? e.message : String(e));
                }
            })();
        """.trimIndent()
        val raw = try {
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(wrapped, null)
            }
            withTimeoutOrNull(60_000L) { deferred.await() }
        } finally {
            asyncJsDeferred = null
            jsBridge.disarm()
        }
        return raw
    }


    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {
        currentProfile = profile
        val ua = if (profile == UserAgentProfile.CUSTOM && !customUA.isNullOrEmpty()) customUA
            else profile.userAgentString
        if (ua != null) {
            webView.settings.userAgentString = ua
        }
        applyViewport()
        if (_currentURL.value.isNotEmpty()) {
            webView.reload()
        }
    }

    fun applyViewport() {
        val vp = currentProfile.viewportSize
        applyViewport(vp.first, vp.second)
    }

    private var lastAppliedViewport: Pair<Int, Int>? = null

    fun applyViewport(cssWidth: Int, cssHeight: Int) {
        val density = webView.resources.displayMetrics.density
        val w = ((cssWidth * density).toInt()).coerceAtLeast(1)
        val h = ((cssHeight * density).toInt()).coerceAtLeast(1)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, w, h)
        lastAppliedViewport = cssWidth to cssHeight
        applyShrinkToFit(cssWidth)
    }

    private suspend fun setUserAgent(profile: UserAgentProfile?, raw: String?): BrowserActionResult {
        if (profile == null || profile == UserAgentProfile.CUSTOM) {
            return BrowserActionResult.error(
                "set_user_agent needs user_agent desktop_chrome or mobile_chrome (aliases: desktop, mobile); " +
                    "got ${raw?.let { "'$it'" } ?: "nothing"}",
            )
        }
        val newProfile = profile
        currentProfile = newProfile
        val ua = newProfile.userAgentString
        withContext(Dispatchers.Main) {
            if (ua != null) {
                webView.settings.userAgentString = ua
            }
            val (vw, vh) = viewportOverride() ?: newProfile.viewportSize
            applyViewport(vw, vh)
            val oldUrl = _currentURL.value
            if (oldUrl.isNotEmpty()) {
                webView.reload()
            }
        }
        val vp = viewportOverride() ?: newProfile.viewportSize
        return BrowserActionResult(
            text = "Switched this tab to ${newProfile.value} (${vp.first}x${vp.second}). Other tabs and the default " +
                "in Browser Settings are unchanged; a tab opened or rebuilt later uses the default.",
        )
    }


    // -- User Navigation --
    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() { webView.reload() }
    fun stopLoading() { webView.stopLoading(); _isLoading.value = false }

    suspend fun reloadAndWait() {
        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true
        val url = _currentURL.value
        if (url.isEmpty() || url == "about:blank") {
            webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        } else {
            webView.reload()
        }
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    suspend fun loadBlankPage() {
        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true
        webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    fun loadURL(urlString: String) {
        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://$normalized"
        _isLoading.value = true
        webView.loadUrl(normalized)
    }


    private suspend fun evaluateJavascript(
        js: String,
        timeoutMs: Long = BrowserActionGuard.JS_EVAL_TIMEOUT_MS,
    ): String = evaluateJavascriptValue(js, timeoutMs) ?: "null"

    private suspend fun evaluateJavascriptValue(
        js: String,
        timeoutMs: Long = BrowserActionGuard.JS_EVAL_TIMEOUT_MS,
    ): String? {
        if (isRendererDead) throw WebViewWedgedException("browser tab renderer process died")
        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(js) { result -> deferred.complete(result ?: "null") }
        }
        val raw = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            markWedged("evaluateJavascript got no callback within ${timeoutMs / 1000}s")
            throw WebViewWedgedException(
                "browser tab stopped responding (JS evaluation timed out after ${timeoutMs / 1000}s)",
            )
        }
        return BrowserJSResult.decode(raw)
    }

    private suspend fun evaluateAndReturn(js: String): BrowserActionResult =
        evaluateActionScript(js) { BrowserActionResult(text = formatJSONResult(it)) }

    private suspend fun evaluateJSAndParse(js: String): BrowserActionResult =
        evaluateActionScript(js) { BrowserActionResult(text = formatJSONResult(it)) }

    private suspend fun evaluateActionScript(
        js: String,
        onJson: (JSONObject) -> BrowserActionResult,
    ): BrowserActionResult {
        val decoded = try {
            evaluateJavascriptValue(js)
        } catch (e: Exception) {
            return BrowserActionResult.error("JavaScript error: ${e.message}")
        }
        return when (val outcome = BrowserJSResult.classify(decoded)) {
            BrowserJSResult.Outcome.NoResult -> BrowserActionResult.error(BrowserJSResult.NO_RESULT_ERROR)
            is BrowserJSResult.Outcome.ScriptError -> BrowserActionResult.error(outcome.message)
            is BrowserJSResult.Outcome.Json -> try {
                onJson(outcome.json)
            } catch (e: Exception) {
                BrowserActionResult.error("JavaScript error: ${e.message}")
            }
            is BrowserJSResult.Outcome.Text -> BrowserActionResult(text = outcome.text)
        }
    }


    private fun formatJSONResult(json: JSONObject): String = buildString {
        when {
            json.optBoolean("clicked") -> {
                val tag = json.optString("tag", "?")
                appendLine("Clicked <$tag>")
                if (json.has("x") && json.has("y")) appendLine("  Position: (${json.optInt("x")}, ${json.optInt("y")})")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.optBoolean("typed") -> {
                val sel = json.optString("selector", "?")
                val len = json.optInt("length", 0)
                append("Typed $len chars into $sel")
            }
            json.optBoolean("scrolled") -> {
                val dir = json.optString("direction", "?")
                val amt = json.optInt("amount", 0)
                appendLine("Scrolled $dir ${amt}px")
                if (json.has("scrollY")) appendLine("  Scroll Y: ${json.optInt("scrollY")}")
                if (json.has("scrollHeight")) appendLine("  Page height: ${json.optInt("scrollHeight")}")
                if (json.has("viewportHeight")) append("  Viewport height: ${json.optInt("viewportHeight")}")
            }
            json.has("scrolledTo") -> {
                val sel = json.optString("scrolledTo")
                val tag = json.optString("tag", "?")
                append("Scrolled to <$tag> ($sel)")
            }
            json.optBoolean("hovered") -> {
                val tag = json.optString("tag", "?")
                appendLine("Hovered <$tag>")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.has("text") && json.has("length") -> {
                val title = json.optString("title", "")
                if (title.isNotEmpty()) appendLine("Title: $title")
                val text = json.optString("text", "")
                val len = json.optInt("length", text.length)
                appendLine("Text ($len chars):")
                append(text.take(10000))
            }
            json.has("count") && json.has("elements") -> {
                val count = json.optInt("count")
                val elements = json.optJSONArray("elements")
                val shown = json.optInt("shown", elements?.length() ?: 0)
                appendLine("Found $count element(s) (showing $shown):")
                if (elements != null) {
                    for (i in 0 until elements.length()) {
                        val el = elements.getJSONObject(i)
                        val idx = el.optInt("index")
                        val tag = el.optString("tag", "?")
                        val text = el.optString("text", "").take(80)
                        val line = buildString {
                            append("  [$idx] <$tag>")
                            val id = el.optString("id", "")
                            if (id.isNotEmpty()) append(" #$id")
                            if (text.isNotEmpty()) append(" \"$text\"")
                            val href = el.optString("href", "")
                            if (href.isNotEmpty()) append(" -> $href")
                        }
                        appendLine(line)
                    }
                }
            }
            else -> {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    appendLine("  $key: ${json.opt(key)}")
                }
            }
        }
    }.trimEnd()

    private fun formatBackboneResult(json: JSONObject): String = buildString {
        val nodeCount = json.optInt("nodeCount")
        val depth = json.optInt("depth")
        val merged = json.optInt("merged")
        appendLine("Page backbone: $nodeCount nodes, depth $depth, $merged merged")

        val backbone = json.optJSONArray("backbone")
        if (backbone != null) {
            for (i in 0 until backbone.length()) {
                formatBackboneNode(backbone.getJSONObject(i), 0, this)
            }
        }
    }.trimEnd()

    private fun formatBackboneNode(node: JSONObject, indent: Int, sb: StringBuilder) {
        val pad = "  ".repeat(indent)
        val tag = node.optString("tag", "?")
        val sel = node.optString("sel", "")
        val rect = node.optString("rect", "")

        val header = buildString {
            append("$pad<$tag>")
            val id = node.optString("id", "")
            if (id.isNotEmpty()) append(" #$id")
            val cls = node.optString("cls", "")
            if (cls.isNotEmpty()) append(" .${cls.replace(" ", ".")}")
            val role = node.optString("role", "")
            if (role.isNotEmpty()) append(" [$role]")
            append(" | $sel | $rect")
        }
        sb.appendLine(header)

        val text = node.optString("text", "")
        if (text.isNotEmpty()) sb.appendLine("$pad  \"$text\"")
        val href = node.optString("href", "")
        if (href.isNotEmpty()) sb.appendLine("$pad  -> $href")
        val img = node.optString("img", "")
        if (img.isNotEmpty()) sb.appendLine("$pad  img: $img")
        val input = node.optString("input", "")
        if (input.isNotEmpty()) sb.appendLine("$pad  input: $input")

        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                formatBackboneNode(children.getJSONObject(i), indent + 1, sb)
            }
        }
    }

    private fun extensionForMimeType(mime: String): String {
        val lower = mime.lowercase().split(";").firstOrNull()?.trim() ?: ""
        return when (lower) {
            "text/html" -> "html"; "text/plain" -> "txt"; "text/css" -> "css"; "text/csv" -> "csv"
            "application/json" -> "json"; "application/xml", "text/xml" -> "xml"
            "application/pdf" -> "pdf"; "image/png" -> "png"; "image/jpeg" -> "jpg"
            "image/gif" -> "gif"; "image/webp" -> "webp"; "image/svg+xml" -> "svg"
            "application/zip" -> "zip"; "application/gzip" -> "gz"
            else -> "bin"
        }
    }

    private fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }


    private fun getCookies(keywords: List<String>?, fuzzy: Boolean): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("get_cookies: no page is loaded (navigate first)")
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("get_cookies: CookieManager unavailable")
        val raw = cookieMgr.getCookie(url).orEmpty()
        if (raw.isEmpty()) {
            return BrowserActionResult(text = "No cookies set for $url")
        }
        val pairs = raw.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
        val filtered = if (keywords.isNullOrEmpty()) pairs else pairs.filter { (name, _) ->
            keywords.any { kw ->
                if (fuzzy) name.contains(kw, ignoreCase = true)
                else name.equals(kw, ignoreCase = true)
            }
        }
        val text = buildString {
            appendLine("Cookies for $url (${filtered.size} of ${pairs.size}):")
            for ((name, value) in filtered) {
                val preview = if (value.length > 80) value.take(77) + "…" else value
                appendLine("  $name = $preview")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }


    private fun setCookies(cookies: List<Map<String, Any?>>?): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("set_cookies: no page is loaded (navigate first)")
        if (cookies == null) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' must be a JSON array of cookie objects " +
                    "(e.g. [{\"name\":\"foo\",\"value\":\"bar\"}]). It was missing or could not be " +
                    "parsed — if you passed it as a string, ensure it is valid JSON; from the CLI " +
                    "prefer --cookies-file <path> to avoid shell escaping.",
            )
        }
        if (cookies.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' array is empty — provide at least one {name, value} object.",
            )
        }
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("set_cookies: CookieManager unavailable")

        val defaultDomain = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

        val httpDateFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }

        val setNames = mutableListOf<String>()
        val domainsTouched = linkedSetOf<String>()
        val failures = mutableListOf<String>()

        for (raw in cookies) {
            val name = cookieString(raw, "name")?.takeIf { it.isNotEmpty() }
            val value = cookieString(raw, "value")
            if (name == null || value == null) {
                failures.add("(missing name/value)")
                continue
            }
            val domain = cookieString(raw, "domain")?.takeIf { it.isNotEmpty() } ?: defaultDomain
            val path = cookieString(raw, "path")?.takeIf { it.isNotEmpty() } ?: "/"

            val sb = StringBuilder()
            sb.append(name).append('=').append(value)
            if (domain.isNotEmpty()) sb.append("; Domain=").append(domain)
            sb.append("; Path=").append(path)
            if (cookieBool(raw, "secure") == true) sb.append("; Secure")
            if (cookieBool(raw, "http_only", "httpOnly") == true) sb.append("; HttpOnly")
            cookieNumber(raw, "expires", "expirationDate")?.takeIf { it > 0 }?.let { expires ->
                val date = java.util.Date(expires.toLong() * 1000L)
                sb.append("; Expires=").append(httpDateFmt.format(date))
            }
            @Suppress("UNUSED_VARIABLE")
            val sameSite = cookieString(raw, "sameSite", "same_site")

            cookieMgr.setCookie(url, sb.toString())
            setNames.add(name)
            domainsTouched.add(domain)
        }
        cookieMgr.flush()

        if (setNames.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: no cookies were set (every entry was invalid: ${failures.joinToString(", ")})",
            )
        }
        val domainLabel = if (domainsTouched.size == 1) domainsTouched.first() else domainsTouched.joinToString(", ")
        var text = "Set ${setNames.size} cookie(s) for $domainLabel: ${setNames.joinToString(", ")}"
        if (failures.isNotEmpty()) {
            text += "\nSkipped ${failures.size} invalid entry(ies): ${failures.joinToString(", ")}"
        }
        return BrowserActionResult(text = text)
    }


    private fun cookieValue(raw: Map<String, Any?>, vararg aliases: String): Any? {
        for (key in aliases) raw[key]?.let { return it }
        val lowered = aliases.map { it.lowercase() }.toSet()
        for ((k, v) in raw) if (k.lowercase() in lowered && v != null) return v
        return null
    }

    private fun cookieString(raw: Map<String, Any?>, vararg aliases: String): String? =
        when (val v = cookieValue(raw, *aliases)) {
            is String -> v
            is Number -> v.toString()
            else -> null
        }

    private fun cookieBool(raw: Map<String, Any?>, vararg aliases: String): Boolean? =
        when (val v = cookieValue(raw, *aliases)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.lowercase() in setOf("true", "1", "yes")
            else -> null
        }

    private fun cookieNumber(raw: Map<String, Any?>, vararg aliases: String): Double? =
        when (val v = cookieValue(raw, *aliases)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }


    private suspend fun waitForDomStable(timeoutMs: Int?): BrowserActionResult {
        val budget = (timeoutMs ?: DEFAULT_DOM_STABLE_TIMEOUT_MS).coerceIn(
            MIN_DOM_STABLE_TIMEOUT_MS, MAX_DOM_STABLE_TIMEOUT_MS,
        )
        val pollInterval = 200L
        var lastSize = -1L
        var stable = false
        val deadline = System.currentTimeMillis() + budget
        val readyState = evaluateJavascript(
            "(function(){try{return document.readyState;}catch(e){return '';}})()"
        ).trim('"')
        if (readyState == "complete") {
            val first = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            delay(50)
            val second = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            if (first == second && first > 0) {
                return BrowserActionResult(
                    text = "DOM stable immediately (readyState=complete, body length=$first)",
                )
            }
        }
        while (System.currentTimeMillis() < deadline) {
            val raw = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            )
            val size = raw.toLongOrNull() ?: -1L
            if (size == lastSize && size >= 0) { stable = true; break }
            lastSize = size
            delay(pollInterval)
        }
        val elapsed = budget - (deadline - System.currentTimeMillis())
        return if (stable) {
            BrowserActionResult(text = "DOM stable after ${elapsed}ms (body length=$lastSize)")
        } else {
            BrowserActionResult(
                text = "DOM did not stabilize within ${budget}ms (last body length=$lastSize)",
                success = false,
            )
        }
    }


    private suspend fun scrollAndCollect(
        scrollCount: Int?,
        itemSelector: String?,
        keywords: List<String>?,
    ): BrowserActionResult {
        val iterations = (scrollCount ?: 5).coerceIn(1, 50)
        val selector = itemSelector?.takeIf { it.isNotBlank() }
            ?: return BrowserActionResult.error("scroll_and_collect requires --item-selector")

        val collected = LinkedHashSet<String>()
        for (i in 0 until iterations) {
            val escaped = selector.replace("\\", "\\\\").replace("'", "\\'")
            val raw = evaluateJavascript(
                """(function(){
                    try {
                        var nodes = document.querySelectorAll('$escaped');
                        var out = [];
                        for (var i=0;i<nodes.length;i++) {
                            var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                            if (t) out.push(t);
                        }
                        return JSON.stringify(out);
                    } catch(e) { return '[]'; }
                })()"""
            )
            try {
                val arr = org.json.JSONArray(raw)
                for (j in 0 until arr.length()) {
                    val s = arr.optString(j)
                    if (s.isNotBlank()) collected.add(s)
                }
            } catch (_: Exception) {  }

            evaluateJavascript("window.scrollBy(0, window.innerHeight);")
            delay(400)
        }

        val filtered = if (keywords.isNullOrEmpty()) collected.toList()
            else collected.filter { text -> keywords.any { k -> text.contains(k, ignoreCase = true) } }

        val text = buildString {
            appendLine("scroll_and_collect: $iterations scrolls, selector='$selector'")
            appendLine("  matched: ${filtered.size} / ${collected.size} total")
            for ((i, item) in filtered.withIndex()) {
                val preview = if (item.length > 160) item.take(157) + "…" else item
                appendLine("  [${i + 1}] $preview")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

}
