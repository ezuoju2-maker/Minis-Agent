package com.openminis.app.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "KaTeXView"

private const val MAX_BITMAP_EDGE_PX = 2048
private const val MAX_BITMAP_PIXELS = 4_000_000

private const val KATEX_WEBVIEW_RELEASED_TAG = "katex-webview-released"

object KaTeXRendererCache {
    data class CacheEntry(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val cssWidth: Int,
        val cssHeight: Int,
    )

    private val cacheBudgetBytes: Int = run {
        val maxHeap = Runtime.getRuntime().maxMemory()
        (maxHeap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    val cache = object : LruCache<String, CacheEntry>(cacheBudgetBytes) {
        override fun sizeOf(key: String, value: CacheEntry): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    fun evictAll() {
        val before = cache.size()
        cache.evictAll()
        android.util.Log.i(
            "KaTeXRendererCache",
            "evictAll: released ~${before / 1024}KB of cached formula bitmaps",
        )
    }

    fun cacheKey(latex: String, displayMode: Boolean, isDark: Boolean): String =
        (if (displayMode) "D:" else "I:") + (if (isDark) "k:" else "l:") + latex
}

@Composable
fun MathBlockView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        KaTeXRenderView(latex = latex, displayMode = true)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXRenderView(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = ChatColors.isDark
    val fontSize = 16f
    val cacheKey = remember(latex, displayMode, isDark) {
        KaTeXRendererCache.cacheKey(latex, displayMode, isDark)
    }

    val cached = remember(cacheKey) { KaTeXRendererCache.cache.get(cacheKey) }

    if (cached != null) {
        Image(
            bitmap = cached.bitmap.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier.size(cached.cssWidth.dp, cached.cssHeight.dp),
        )
        return
    }

    var renderedBitmap by remember(cacheKey) { mutableStateOf<Bitmap?>(null) }
    var renderedCssWidth by remember(cacheKey) { mutableStateOf(0) }
    var renderedCssHeight by remember(cacheKey) { mutableStateOf(0) }
    var renderError by remember(cacheKey) { mutableStateOf<String?>(null) }

    if (renderedBitmap != null) {
        Image(
            bitmap = renderedBitmap!!.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier.size(renderedCssWidth.dp, renderedCssHeight.dp),
        )
    } else if (renderError != null) {
        Text(
            text = latex,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .then(
                    if (displayMode) Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                    else Modifier
                        .widthIn(min = 20.dp)
                        .height(20.dp)
                ),
        )

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.textZoom = 100
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onRendered(width: Int, height: Int, error: String) {
                            if (error.isNotEmpty()) {
                                AppLogger.warning(TAG, "KaTeX render failed: $error · latex=${latex.take(80)}")
                                renderError = error
                                return
                            }
                            if (width <= 0 || height <= 0) {
                                AppLogger.warning(TAG, "KaTeX render produced zero dimensions · latex=${latex.take(80)}")
                                renderError = "zero dimensions"
                                return
                            }
                            val scale = ctx.resources.displayMetrics.density
                            val bitmapW = (width * scale).toInt()
                            val bitmapH = (height * scale).toInt()

                            post {
                                layoutParams = ViewGroup.LayoutParams(bitmapW, bitmapH)
                                requestLayout()
                                postDelayed({
                                    if (tag == KATEX_WEBVIEW_RELEASED_TAG) return@postDelayed
                                    val edgeScale = minOf(
                                        1f,
                                        MAX_BITMAP_EDGE_PX.toFloat() / bitmapW,
                                        MAX_BITMAP_EDGE_PX.toFloat() / bitmapH,
                                    )
                                    val pixelScale =
                                        if (bitmapW.toLong() * bitmapH > MAX_BITMAP_PIXELS) {
                                            kotlin.math.sqrt(
                                                MAX_BITMAP_PIXELS.toDouble() /
                                                    (bitmapW.toDouble() * bitmapH),
                                            ).toFloat()
                                        } else {
                                            1f
                                        }
                                    val capScale = minOf(edgeScale, pixelScale)
                                    val capW = (bitmapW * capScale).toInt().coerceAtLeast(1)
                                    val capH = (bitmapH * capScale).toInt().coerceAtLeast(1)
                                    if (capScale < 1f) {
                                        AppLogger.info(
                                            TAG,
                                            "bitmap clamped ${bitmapW}x$bitmapH -> ${capW}x$capH " +
                                                "(scale=$capScale)",
                                        )
                                    }
                                    val bitmap = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bitmap)
                                    if (capScale < 1f) canvas.scale(capScale, capScale)
                                    draw(canvas)
                                    KaTeXRendererCache.cache.put(
                                        cacheKey,
                                        KaTeXRendererCache.CacheEntry(
                                            bitmap = bitmap,
                                            width = capW,
                                            height = capH,
                                            cssWidth = width,
                                            cssHeight = height,
                                        )
                                    )
                                    renderedCssWidth = width
                                    renderedCssHeight = height
                                    renderedBitmap = bitmap
                                }, 100)
                            }
                        }
                    }, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(view: android.webkit.WebView?, handler: android.webkit.HttpAuthHandler?, host: String?, realm: String?) {
                            if (handler != null && com.openminis.app.network.WebViewNetworkProxy.answerAuth(handler, host, realm, view?.url)) return
                            super.onReceivedHttpAuthRequest(view, handler, host, realm)
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            runCatching { view?.tag = KATEX_WEBVIEW_RELEASED_TAG }
                            return com.openminis.app.ui.webview.WebViewRenderProcess
                                .handle("KaTeXView", detail)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val escapedLatex = latex
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", "\\n")
                                .replace("\r", "")
                            evaluateJavascript(
                                "renderMath('$escapedLatex', $displayMode, $fontSize, $isDark)",
                                null
                            )
                        }
                    }

                    loadUrl("file:///android_asset/katex/katex-render.html")
                }
            },
            onRelease = { wv ->
                wv.tag = KATEX_WEBVIEW_RELEASED_TAG
                runCatching { wv.stopLoading() }
                runCatching { wv.destroy() }
            },
            modifier = Modifier.height(0.dp),
        )
    }
}
