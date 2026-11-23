package com.openminis.app.ui.preview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

object WebPreviewShortcut {

    fun pin(
        context: Context,
        sessionId: String,
        url: String,
        title: String,
        favicon: Bitmap?,
    ) {
        if (!url.startsWith("file://")) {
            AppLogger.warning(TAG, "refuse to pin non-file URL: ${url.take(80)}")
            return
        }
        if (sessionId.isBlank()) {
            AppLogger.warning(TAG, "pin: empty sessionId")
            return
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.webpreview_pin_to_home_unsupported),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val absPath = Uri.parse(url).path.orEmpty()
        if (absPath.isBlank()) {
            AppLogger.warning(TAG, "pin: empty path in $url")
            return
        }
        val resourcePath = absPath.removePrefix("/var/minis").let {
            if (it.startsWith("/")) it else "/$it"
        }

        val shortcutId = "html_preview_${(sessionId + resourcePath).hashCode().toUInt().toString(16)}"

        val deepLink = Uri.Builder()
            .scheme("minis")
            .authority("session")
            .path("/$sessionId$resourcePath")
            .appendQueryParameter("title", title)
            .build()

        val launchIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val shortLabel = title.ifBlank {
            java.io.File(absPath).nameWithoutExtension.ifBlank { "Mini app" }
        }
        val icon = favicon
            ?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithBitmap(generateMonogramBitmap(shortLabel, shortcutId))

        val info = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(shortLabel.take(20))
            .setLongLabel(shortLabel.take(40))
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        val ok = ShortcutManagerCompat.requestPinShortcut(context, info, null)
        AppLogger.info(TAG, "pin shortcut id=$shortcutId requested=$ok")
        if (ok) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.webpreview_pin_to_home_requested),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private const val TAG = "WebPreviewShortcut"

    private fun generateMonogramBitmap(label: String, seed: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val color = MONOGRAM_PALETTE[
            (seed.hashCode().toUInt() % MONOGRAM_PALETTE.size.toUInt()).toInt()
        ]
        val bgPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
        val char = firstGrapheme(label)
        val isEmoji = char.codePoints().anyMatch { cp ->
            (cp in 0x1F300..0x1F5FF) || (cp in 0x1F600..0x1F64F) ||
                (cp in 0x1F680..0x1F6FF) || (cp in 0x1F900..0x1F9FF) ||
                (cp in 0x1FA70..0x1FAFF) || (cp in 0x2600..0x26FF) ||
                (cp in 0x2700..0x27BF)
        }
        val display = if (isEmoji) char else char.uppercase()
        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = if (isEmoji) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = if (isEmoji) size * 0.62f else size * 0.55f
            typeface = if (isEmoji) {
                android.graphics.Typeface.DEFAULT
            } else {
                android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD,
                )
            }
        }
        val fm = textPaint.fontMetrics
        val baselineY = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(display, size / 2f, baselineY, textPaint)
        return bmp
    }

    private fun firstGrapheme(s: String): String {
        val trimmed = s.trimStart()
        if (trimmed.isEmpty()) return "M"
        val bi = java.text.BreakIterator.getCharacterInstance()
        bi.setText(trimmed)
        val end = bi.next()
        return if (end == java.text.BreakIterator.DONE || end <= 0) {
            trimmed.take(1)
        } else {
            trimmed.substring(0, end)
        }
    }

    private val MONOGRAM_PALETTE: IntArray = intArrayOf(
        0xFF1F8FFF.toInt(),
        0xFF34C759.toInt(),
        0xFFFF9F0A.toInt(),
        0xFFFF453A.toInt(),
        0xFFAF52DE.toInt(),
        0xFFFF2D55.toInt(),
        0xFF5AC8FA.toInt(),
        0xFFFFCC00.toInt(),
        0xFF00B894.toInt(),
        0xFFFF6B6B.toInt(),
        0xFF6C5CE7.toInt(),
        0xFFE17055.toInt(),
    )
}
