package com.openminis.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri

val LocalInAppBrowserLauncher = compositionLocalOf<(String) -> Unit> {
    { _ -> }
}

fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun InAppBrowserHost(
    context: Context,
    content: @Composable () -> Unit,
) {
    var previewUrl by remember { mutableStateOf<String?>(null) }

    val launcher = remember(context) {
        { url: String ->
            val lower = url.trim().lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                previewUrl = url
            } else {
                openExternalUrl(context, url)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalInAppBrowserLauncher provides launcher,
    ) {
        content()
    }

    previewUrl?.let { url ->
        UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }
}
