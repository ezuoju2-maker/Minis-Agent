package com.openminis.app.ui.preview

import androidx.compose.ui.res.stringResource
import com.openminis.app.R

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.theme.ChatColors

private const val WEBAPP_PIN_ENTRY_ENABLED = false

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun WebPreviewBottomSheet(
    holder: WebViewHolder,
    onDismiss: () -> Unit,
    onExpandFullscreen: (() -> Unit)? = null,
    fallbackTitle: String = "",
    pinSessionId: String? = null,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val view = LocalView.current
    val darkTheme = ChatColors.isDark

    LaunchedEffect(holder) {
        holder.startIfNeeded()
    }

    DisposableEffect(holder) {
        onDispose {
            holder.detach()
        }
    }

    DisposableEffect(view, darkTheme) {
        val window = (view.context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            onDispose {
                controller.isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            AppLogger.info("WebPreviewSheet", "dismiss")
            onDismiss()
        },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        val localView = LocalView.current
        DisposableEffect(localView, darkTheme) {
            val win = findDialogWindow(localView)
            AppLogger.info("WebPreviewSheet", "edgeToEdge: dialogWindow=${win?.javaClass?.simpleName ?: "null"}")
            win?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                @Suppress("DEPRECATION")
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                val ctrl = WindowInsetsControllerCompat(w, w.decorView)
                ctrl.isAppearanceLightStatusBars = !darkTheme
                ctrl.isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose { }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(1f),
        ) {
            val displayTitle = holder.pageTitle.ifBlank {
                fallbackTitle.ifBlank {
                    runCatching { Uri.parse(holder.currentUrl).host }.getOrNull()
                        ?: holder.currentUrl
                }
            }
            val displayHost = runCatching { Uri.parse(holder.currentUrl).host }
                .getOrNull()
                ?.removePrefix("www.")
                ?: holder.currentUrl
            WebPreviewToolbar(
                topInsetPadding = true,
                title = displayTitle,
                subtitle = displayHost,
                isLoading = holder.isLoading,
                desktopMode = holder.desktopMode,
                onClose = onDismiss,
                onReload = { holder.reload() },
                onStop = { holder.stopLoading() },
                onOpenExternal = {
                    openExternalFromSheet(context, holder.currentUrl)
                },
                onToggleDesktop = { holder.toggleDesktopMode() },
                onPinToHome = if (pinSessionId != null && holder.currentUrl.startsWith("file://") && WEBAPP_PIN_ENTRY_ENABLED) {
                    {
                        com.openminis.app.ui.preview.WebPreviewShortcut.pin(
                            context = context,
                            sessionId = pinSessionId,
                            url = holder.currentUrl,
                            title = displayTitle,
                            favicon = holder.pageFavicon,
                        )
                    }
                } else null,
                onExpand = onExpandFullscreen?.let { expandCb ->
                    {
                        AppLogger.info("WebPreviewSheet", "expand → fullscreen")
                        holder.detach()
                        expandCb()
                    }
                },
            )
            if (holder.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = {
                        holder.webView.setOnTouchListener { v, event ->
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
                        holder.webView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun WebPreviewToolbar(
    title: String,
    subtitle: String,
    isLoading: Boolean,
    desktopMode: Boolean,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onOpenExternal: () -> Unit,
    onToggleDesktop: () -> Unit,
    onPinToHome: (() -> Unit)? = null,
    onExpand: (() -> Unit)?,
    topInsetPadding: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .let { if (topInsetPadding) it.windowInsetsPadding(WindowInsets.statusBars) else it }
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIcon(
            icon = Icons.Default.Close,
            description = stringResource(R.string.webpreview_close),
            onClick = onClose,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isLoading) {
            ToolbarIcon(
                icon = Icons.Default.Stop,
                description = stringResource(R.string.webpreview_stop),
                onClick = onStop,
            )
        }
        Box {
            ToolbarIcon(
                icon = Icons.Default.MoreVert,
                description = stringResource(R.string.webpreview_more),
                onClick = { menuOpen = true },
            )
            com.openminis.app.ui.components.MinisMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.webpreview_open_external)) },
                    leadingIcon = {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onOpenExternal()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.webpreview_reload)) },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onReload()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            if (desktopMode) stringResource(R.string.webpreview_mobile_site)
                            else stringResource(R.string.webpreview_desktop_site)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (desktopMode) Icons.Default.PhoneAndroid
                            else Icons.Default.DesktopWindows,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onToggleDesktop()
                    },
                )
                if (onExpand != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.webpreview_open_fullscreen)) },
                        leadingIcon = {
                            Icon(Icons.Default.Fullscreen, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onExpand()
                        },
                    )
                }
                if (onPinToHome != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.webpreview_pin_to_home)) },
                        leadingIcon = {
                            Icon(Icons.Default.AddToHomeScreen, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onPinToHome()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun findDialogWindow(view: android.view.View): android.view.Window? {
    var p: android.view.ViewParent? = view.parent
    while (p != null) {
        if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window
        p = p.parent
    }
    return null
}

internal fun openExternalFromSheet(context: android.content.Context, url: String) {
    AppLogger.info("WebPreviewSheet", "open external: $url")
    val intent = if (url.startsWith("file://")) {
        runCatching {
            val raw = Uri.parse(url).path ?: return@runCatching null
            val file = java.io.File(java.net.URLDecoder.decode(raw, "UTF-8"))
            if (!file.exists()) return@runCatching null
            val authority = context.packageName + ".fileprovider"
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                context, authority, file,
            )
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "text/html"
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent == null) {
        AppLogger.warning("WebPreviewSheet", "could not build external intent for $url")
        return
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            AppLogger.warning("WebPreviewSheet", "external open failed: ${it.message}")
        }
}
