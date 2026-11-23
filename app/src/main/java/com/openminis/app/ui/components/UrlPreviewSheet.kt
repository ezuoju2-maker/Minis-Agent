package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import com.openminis.app.ui.preview.WebPreviewBottomSheet
import com.openminis.app.ui.preview.rememberWebViewHolder

@Composable
fun UrlPreviewSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val holder = rememberWebViewHolder(url)
    WebPreviewBottomSheet(
        holder = holder,
        onDismiss = {
            holder.destroy()
            onDismiss()
        },
    )
}
