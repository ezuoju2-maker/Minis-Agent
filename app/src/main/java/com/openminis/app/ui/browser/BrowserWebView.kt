package com.openminis.app.ui.browser

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("ClickableViewAccessibility")
@Composable
fun BrowserWebView(
    webView: WebView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val mounted = container.getChildAt(0)
            if (mounted !== webView) {
                container.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_POINTER_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { container ->
            container.removeAllViews()
        },
        modifier = modifier,
    )
}
