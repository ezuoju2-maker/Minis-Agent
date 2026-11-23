package com.openminis.app.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object HeadlessRenderHost {
    private const val TAG = "HeadlessRenderHost"

    const val IDLE_RELEASE_MS = 15_000L
    const val MAX_EDGE_PX = 4096
    private const val VISUAL_STATE_TIMEOUT_MS = 1_500L
    private const val SETTLE_TIMEOUT_MS = 700L
    private const val QUIET_MS = 150L
    private const val MIN_FRAMES = 2
    private const val MAX_HOST_FAILURES = 3

    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null
    private var hostW = 0
    private var hostH = 0
    private var latest: Image? = null
    private var frameCount = 0L
    private var lastFrameAt = 0L
    private var hostFailures = 0
    private var visualStateRequest = 0L

    @Volatile
    var disabled: String? = null
        private set

    private val releaseTask = Runnable { release("idle") }

    suspend fun capture(webView: WebView, w: Int, h: Int): Bitmap? {
        if (disabled != null) return null
        return mutex.withLock { withContext(Dispatchers.Main) { captureOnMain(webView, w, h) } }
    }

    private suspend fun captureOnMain(webView: WebView, w: Int, h: Int): Bitmap? {
        if (webView.parent != null || webView.isAttachedToWindow) return null
        if (w <= 0 || h <= 0 || w > MAX_EDGE_PX || h > MAX_EDGE_PX) return null

        main.removeCallbacks(releaseTask)
        val t0 = SystemClock.elapsedRealtime()
        var mainBlockMs = 0L
        fun <T> onMain(block: () -> T): T {
            val s = SystemClock.elapsedRealtime()
            try { return block() } finally { mainBlockMs += SystemClock.elapsedRealtime() - s }
        }
        var image: Image? = null
        var scrollbars: Pair<Boolean, Boolean>? = null
        try {
            val host = onMain { ensureHost(webView.context, w, h) } ?: return null
            val tHost = SystemClock.elapsedRealtime()
            onMain {
                scrollbars = webView.isVerticalScrollBarEnabled to webView.isHorizontalScrollBarEnabled
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false
                host.addView(webView, FrameLayout.LayoutParams(w, h))
            }
            val ready = awaitVisualState(webView)
            val tReady = SystemClock.elapsedRealtime()
            val start = frameCount
            onMain { webView.invalidate() }
            val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                while (frameCount < start + MIN_FRAMES ||
                    SystemClock.elapsedRealtime() - lastFrameAt < QUIET_MS
                ) delay(8)
                true
            } == true
            val gotFrames = frameCount >= start + 1
            val tFrame = SystemClock.elapsedRealtime()
            if (webView.parent !== host) {
                Log.w(TAG, "WebView left the host during capture; falling back")
                return null
            }
            if (!gotFrames || latest == null) {
                Log.w(TAG, "No composited frame (ready=$ready frames=${frameCount - start}); falling back")
                return null
            }
            image = latest
            latest = null
            onMain { host.removeView(webView) }
            val owned = image!!
            val bitmap = withContext(Dispatchers.Default) { toBitmap(owned, w, h) }
            Log.i(
                TAG,
                "gpu capture ${w}x$h total=${SystemClock.elapsedRealtime() - t0}ms " +
                    "host=${tHost - t0}ms ready=${tReady - tHost}ms($ready) " +
                    "frame=${tFrame - tReady}ms(frames=${frameCount - start} settled=$settled) " +
                    "mainBlock=${mainBlockMs}ms",
            )
            return bitmap
        } catch (e: Exception) {
            Log.w(TAG, "gpu capture failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            image?.close()
            scrollbars?.let { (v, hz) ->
                webView.isVerticalScrollBarEnabled = v
                webView.isHorizontalScrollBarEnabled = hz
            }
            val host = container
            if (host != null && webView.parent === host) host.removeView(webView)
            main.postDelayed(releaseTask, IDLE_RELEASE_MS)
        }
    }

    private suspend fun awaitVisualState(webView: WebView): Boolean {
        val done = CompletableDeferred<Unit>()
        val id = ++visualStateRequest
        webView.postVisualStateCallback(
            id,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId == id) done.complete(Unit)
                }
            },
        )
        return withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) { done.await() } != null
    }

    private fun ensureHost(context: Context, w: Int, h: Int): FrameLayout? {
        val existing = container
        if (existing != null && presentation != null && hostW == w && hostH == h) return existing
        release(if (existing == null) "create" else "resize")

        val app = context.applicationContext
        var r: ImageReader? = null
        var vd: VirtualDisplay? = null
        try {
            val dm = app.getSystemService(DisplayManager::class.java) ?: return fail("no DisplayManager")
            r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ rr ->
                val img = runCatching { rr.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = img
                frameCount++
                lastFrameAt = SystemClock.elapsedRealtime()
            }, main)
            vd = dm.createVirtualDisplay(
                "minis-headless-browser", w, h, app.resources.displayMetrics.densityDpi,
                r.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: return fail("createVirtualDisplay returned null").also { r.close() }

            val p = Presentation(app, vd.display)
            p.window?.apply {
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
            }
            val c = FrameLayout(p.context)
            p.setContentView(c)
            p.show()

            reader = r
            display = vd
            presentation = p
            container = c
            hostW = w
            hostH = h
            hostFailures = 0
            Log.i(TAG, "host created ${w}x$h dpi=${app.resources.displayMetrics.densityDpi}")
            return c
        } catch (e: Exception) {
            runCatching { vd?.release() }
            runCatching { r?.close() }
            return fail("host setup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fail(reason: String): FrameLayout? {
        hostFailures++
        Log.w(TAG, "$reason (failure $hostFailures/$MAX_HOST_FAILURES)")
        if (hostFailures >= MAX_HOST_FAILURES) {
            disabled = reason
            Log.w(TAG, "GPU capture disabled for this process: $reason")
        }
        return null
    }

    private fun release(why: String) {
        if (presentation == null && display == null && reader == null) return
        main.removeCallbacks(releaseTask)
        container?.removeAllViews()
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        latest?.close()
        latest = null
        runCatching { reader?.close() }
        presentation = null
        display = null
        reader = null
        container = null
        Log.i(TAG, "host released ($why) ${hostW}x$hostH")
        hostW = 0
        hostH = 0
    }

    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val src = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytes = w * pixelStride
        val packed = ByteBuffer.allocateDirect(rowBytes * h)
        val row = ByteArray(rowBytes)
        for (y in 0 until h) {
            src.position(y * rowStride)
            src.get(row, 0, rowBytes)
            packed.put(row)
        }
        packed.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(packed) }
    }
}
