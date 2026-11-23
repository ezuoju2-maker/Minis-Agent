package com.openminis.app.ui.webview

import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import com.openminis.app.logging.AppLogger

object WebViewRenderProcess {

    private const val TAG = "WebViewRenderProcess"

    fun handle(owner: String, detail: RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash()
        val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            detail?.rendererPriorityAtExit()
        } else {
            null
        }
        AppLogger.warning(
            TAG,
            "renderer gone owner=$owner didCrash=$didCrash priorityAtExit=$priority — " +
                "handled, host process kept alive",
        )
        return true
    }
}

fun WebView.onRenderProcessGoneHandled(owner: String, detail: RenderProcessGoneDetail?): Boolean =
    WebViewRenderProcess.handle(owner, detail)
