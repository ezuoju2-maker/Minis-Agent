package com.openminis.app.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class KatexRenderResult(val bitmap: Bitmap, val size: IntSize)

internal object KatexWebViewPool {

    private const val TAG = "KatexWebViewPool"
    private const val ASSET_HTML = "file:///android_asset/katex/katex-render.html"
    private const val DEFAULT_FONT_SIZE_PX = 17
    private const val RENDER_TIMEOUT_MS = 4_000L
    private const val LAYOUT_PIXELS = 8192

    private const val MAX_BITMAP_EDGE_PX = 2048
    private const val MAX_BITMAP_PIXELS = 4_000_000

    private val cacheBudgetBytes: Int = run {
        val maxHeap = Runtime.getRuntime().maxMemory()
        (maxHeap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    private val cache = object : LruCache<String, KatexRenderResult>(cacheBudgetBytes) {
        override fun sizeOf(key: String, value: KatexRenderResult): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    fun evictAll() {
        val before = cache.size()
        cache.evictAll()
        android.util.Log.i(TAG, "evictAll: released ~${before / 1024}KB of cached formula bitmaps")
    }

    fun releaseWebView() {
        runOnMain {
            webView?.let {
                runCatching { it.destroy() }
                android.util.Log.i(TAG, "releaseWebView: offscreen KaTeX WebView destroyed")
            }
            webView = null
            isReady = false
        }
    }

    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var isReady = false
    @Volatile
    private var pending: CompletableDeferred<Triple<Int, Int, String>>? = null

    suspend fun render(
        context: Context,
        latex: String,
        displayMode: Boolean,
        isDark: Boolean,
        fontSizePx: Int = DEFAULT_FONT_SIZE_PX,
    ): KatexRenderResult? {
        val key = cacheKey(latex, displayMode, isDark, fontSizePx)
        cache.get(key)?.let { return it }
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            val result = renderInternal(context.applicationContext, latex, displayMode, isDark, fontSizePx)
            if (result != null) cache.put(key, result)
            result
        }
    }

    private suspend fun renderInternal(
        appContext: Context,
        latex: String,
        displayMode: Boolean,
        isDark: Boolean,
        fontSizePx: Int,
    ): KatexRenderResult? {
        val wv = ensureWebView(appContext) ?: return null
        if (!awaitReady()) return null

        val deferred = CompletableDeferred<Triple<Int, Int, String>>()
        pending = deferred

        val js = "renderMath(" +
            "'${latex.escapeForJs()}', " +
            "$displayMode, " +
            "$fontSizePx, " +
            "$isDark" +
            ")"
        runOnMain { wv.evaluateJavascript(js, null) }

        val (w, h, err) = withTimeoutOrNull(RENDER_TIMEOUT_MS) { deferred.await() }
            ?: Triple(0, 0, "timeout")
        pending = null
        if (err.isNotEmpty() || w <= 0 || h <= 0) {
            android.util.Log.w(TAG, "render failed latex='${latex.take(40)}' err=$err w=$w h=$h")
            return null
        }
        return runOnMainSync { snapshot(wv, w, h) }
    }

    private suspend fun ensureWebView(appContext: Context): WebView? {
        webView?.let { return it }
        return runOnMainSync { createWebView(appContext) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(appContext: Context): WebView? {
        webView?.let { return it }
        val wv = WebView(appContext).apply {
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(LAYOUT_PIXELS, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(LAYOUT_PIXELS, android.view.View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, LAYOUT_PIXELS, LAYOUT_PIXELS)
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(JsBridge { w, h, err ->
                pending?.complete(Triple(w, h, err))
            }, "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onReceivedHttpAuthRequest(view: android.webkit.WebView?, handler: android.webkit.HttpAuthHandler?, host: String?, realm: String?) {
                    if (handler != null && com.openminis.app.network.WebViewNetworkProxy.answerAuth(handler, host, realm, view?.url)) return
                    super.onReceivedHttpAuthRequest(view, handler, host, realm)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isReady = true
                    readyDeferred?.complete(Unit)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    runCatching { pending?.complete(Triple(0, 0, "render process gone")) }
                    runCatching { readyDeferred?.complete(Unit) }
                    runCatching { releaseWebView() }
                    return com.openminis.app.ui.webview.WebViewRenderProcess
                        .handle("KatexWebViewPool", detail)
                }
            }
            loadUrl(ASSET_HTML)
        }
        webView = wv
        return wv
    }

    private fun snapshot(wv: WebView, w: Int, h: Int): KatexRenderResult? {
        return try {
            val density = wv.context.resources.displayMetrics.density
            val rawW = (w * density).toInt().coerceAtLeast(1)
            val rawH = (h * density).toInt().coerceAtLeast(1)

            val edgeScale = minOf(
                1f,
                MAX_BITMAP_EDGE_PX.toFloat() / rawW,
                MAX_BITMAP_EDGE_PX.toFloat() / rawH,
            )
            val pixelScale = if (rawW.toLong() * rawH > MAX_BITMAP_PIXELS) {
                kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() / (rawW.toDouble() * rawH)).toFloat()
            } else {
                1f
            }
            val scale = minOf(edgeScale, pixelScale)

            val pxW = (rawW * scale).toInt().coerceAtLeast(1)
            val pxH = (rawH * scale).toInt().coerceAtLeast(1)
            if (scale < 1f) {
                android.util.Log.i(
                    TAG,
                    "snapshot clamped ${rawW}x$rawH -> ${pxW}x$pxH (scale=$scale) " +
                        "saved=${(rawW.toLong() * rawH - pxW.toLong() * pxH) * 4 / 1024}KB",
                )
            }
            val bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (scale < 1f) canvas.scale(scale, scale)
            wv.draw(canvas)
            KatexRenderResult(bitmap, IntSize(w, h))
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "snapshot failed: ${e.message}")
            null
        }
    }

    @Volatile
    private var readyDeferred: CompletableDeferred<Unit>? = null

    private suspend fun awaitReady(): Boolean {
        if (isReady) return true
        val d = readyDeferred ?: CompletableDeferred<Unit>().also { readyDeferred = it }
        val ok = withTimeoutOrNull(RENDER_TIMEOUT_MS) { d.await() } != null
        if (!ok) android.util.Log.w(TAG, "WebView never reached ready state")
        return ok
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private suspend fun <T> runOnMainSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val out = CompletableDeferred<T>()
        mainHandler.post {
            try {
                out.complete(block())
            } catch (t: Throwable) {
                out.completeExceptionally(t)
            }
        }
        return out.await()
    }

    private fun cacheKey(latex: String, displayMode: Boolean, isDark: Boolean, fontSizePx: Int): String {
        val d = if (displayMode) 'D' else 'I'
        val k = if (isDark) 'k' else 'l'
        return "$d:$k:$fontSizePx:$latex"
    }

    private fun String.escapeForJs(): String =
        this.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

    private class JsBridge(private val onResult: (Int, Int, String) -> Unit) {
        @JavascriptInterface
        fun onRendered(width: Int, height: Int, error: String) {
            onResult(width, height, error)
        }
    }
}
