package com.openminis.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

@Composable
fun FullscreenImageViewer(
    model: Any,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
            ?: findWindowViaDialog(view)
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val prevStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val prevNavBarColor = window?.navigationBarColor

        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            prevLightStatus?.let { controller.isAppearanceLightStatusBars = it }
            prevLightNav?.let { controller.isAppearanceLightNavigationBars = it }
            @Suppress("DEPRECATION")
            prevStatusBarColor?.let { window.statusBarColor = it }
            @Suppress("DEPRECATION")
            prevNavBarColor?.let { window.navigationBarColor = it }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogContainer = LocalView.current.parent as? android.view.ViewGroup
        DisposableEffect(dialogContainer) {
            val win = dialogContainer?.let { findDialogWindow(it) }
            win?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                val ctrl = WindowInsetsControllerCompat(w, w.decorView)
                ctrl.isAppearanceLightStatusBars = false
                ctrl.isAppearanceLightNavigationBars = false
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            onDispose {  }
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var showChrome by remember { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            },
                            onTap = { showChrome = !showChrome },
                        )
                    },
            )

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val pillShape = RoundedCornerShape(32.dp)
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .background(color = Color(0xFF3A3A3C), shape = pillShape)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.15f),
                            shape = pillShape,
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImageActionButton(
                        icon = Icons.Outlined.ContentCopy,
                        label = stringResource(R.string.image_action_copy),
                        onClick = {
                            copyBitmapToClipboard(context, scope, model)
                        },
                    )
                    var sharing by remember { mutableStateOf(false) }
                    ImageActionButton(
                        icon = Icons.Outlined.Share,
                        label = stringResource(R.string.image_action_share),
                        onClick = onClick@{
                            if (sharing) return@onClick
                            sharing = true
                            scope.launch {
                                try {
                                    shareImage(context, model)
                                } finally {
                                    sharing = false
                                }
                            }
                        },
                    )
                    val savedToAlbumMsg = stringResource(R.string.image_saved_to_album_toast)
                    val saveFailedMsg = stringResource(R.string.image_save_failed_toast)
                    ImageActionButton(
                        icon = Icons.Outlined.Download,
                        label = stringResource(R.string.image_action_save),
                        onClick = {
                            scope.launch {
                                val saved = saveImageToGallery(context, model)
                                val msg = if (saved) savedToAlbumMsg else saveFailedMsg
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
}


@Composable
internal fun ImageActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}


internal suspend fun loadBitmap(context: Context, model: Any): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(context)
            val req = ImageRequest.Builder(context).data(model)
                .size(coil.size.Size.ORIGINAL)
                .allowHardware(false)
                .build()
            val result = loader.execute(req)
            (result as? SuccessResult)?.drawable?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

internal fun copyBitmapToClipboard(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    model: Any,
) {
    scope.launch(Dispatchers.IO) {
        try {
            val file = writeShareFile(context, model, "clipboard_img")
                ?: error("decode failed")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            context.grantUriPermission("*", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newUri(context.contentResolver, "image", uri)
            withContext(Dispatchers.Main) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.image_copied_toast), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.image_copy_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

internal suspend fun shareImage(context: Context, model: Any) {
    try {
        val file = writeShareFile(context, model, "share_img") ?: run {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
            }
            return
        }
        withContext(Dispatchers.IO) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/png"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.image_share_chooser_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.image_share_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
}


private const val EXPORT_TAG = "ImageExport"

internal class OriginalImage(val bytes: ByteArray, val mime: String, val extension: String)

internal suspend fun loadOriginalImage(context: Context, model: Any): OriginalImage? =
    withContext(Dispatchers.IO) {
        try {
            val bytes: ByteArray = when (model) {
                is File -> model.takeIf { it.isFile }?.readBytes()
                is Uri -> readUri(context, model)
                is String -> when {
                    model.startsWith("http://") || model.startsWith("https://") ->
                        com.openminis.app.network.NetworkUrlRequest.open(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, model).use { r -> r.body().use { it.readBytes() } }
                    model.startsWith("content://") || model.startsWith("file://") ->
                        readUri(context, Uri.parse(model))
                    model.startsWith("/") -> File(model).takeIf { it.isFile }?.readBytes()
                    else -> null
                }
                else -> null
            } ?: return@withContext null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val mime = opts.outMimeType ?: return@withContext null
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            Log.i(EXPORT_TAG, "original ${opts.outWidth}x${opts.outHeight} $mime ${bytes.size} bytes")
            OriginalImage(bytes, mime, ext)
        } catch (e: Exception) {
            Log.w(EXPORT_TAG, "original unreadable: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            Log.w(EXPORT_TAG, "original too large to read into memory")
            null
        }
    }

private fun readUri(context: Context, uri: Uri): ByteArray? =
    if (uri.scheme == "file") uri.path?.let { File(it) }?.takeIf { it.isFile }?.readBytes()
    else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

private suspend fun writeShareFile(context: Context, model: Any, prefix: String): File? {
    val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
    val stamp = System.currentTimeMillis()
    loadOriginalImage(context, model)?.let { original ->
        return withContext(Dispatchers.IO) {
            File(shareDir, "${prefix}_$stamp.${original.extension}").also { it.writeBytes(original.bytes) }
        }
    }
    val bmp = loadBitmap(context, model) ?: return null
    return withContext(Dispatchers.IO) {
        File(shareDir, "${prefix}_$stamp.png").also { f ->
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

internal suspend fun saveImageToGallery(context: Context, model: Any): Boolean {
    val original = loadOriginalImage(context, model)
    if (original != null) return saveBytesToGallery(context, original)
    val bmp = loadBitmap(context, model) ?: return false
    Log.w(EXPORT_TAG, "original unavailable — saving full-size decode ${bmp.width}x${bmp.height} as PNG")
    return saveToGallery(context, bmp)
}

private suspend fun saveBytesToGallery(context: Context, original: OriginalImage): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val filename = "minis_${System.currentTimeMillis()}.${original.extension}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, original.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DeepSeek")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use { it.write(original.bytes) }
                    ?: return@withContext false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val minisDir = File(dir, "DeepSeek").also { it.mkdirs() }
                File(minisDir, filename).writeBytes(original.bytes)
            }
            Log.i(EXPORT_TAG, "saved ORIGINAL ${original.mime} ${original.bytes.size} bytes as $filename")
            true
        } catch (e: Exception) {
            Log.w(EXPORT_TAG, "save original failed: ${e.message}")
            false
        }
    }

internal suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val filename = "minis_${System.currentTimeMillis()}.png"
            val stream: OutputStream?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DeepSeek")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                stream = context.contentResolver.openOutputStream(uri)
                stream?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val minisDir = File(dir, "DeepSeek").also { it.mkdirs() }
                val file = File(minisDir, filename)
                stream = file.outputStream()
                stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }


private fun findWindowViaDialog(view: android.view.View): android.view.Window? {
    var v: android.view.View? = view
    while (v != null) {
        val ctx = v.context
        if (ctx is android.app.Activity) return ctx.window
        v = v.parent as? android.view.View
    }
    return null
}

private fun findDialogWindow(root: android.view.ViewGroup): android.view.Window? {
    var p: android.view.ViewParent? = root.parent
    while (p != null) {
        if (p is android.view.View) {
            val ctx = p.context
            if (ctx is android.app.Activity) return ctx.window
        }
        p = (p as? android.view.View)?.parent
    }
    return null
}
