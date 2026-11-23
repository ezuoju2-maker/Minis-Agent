package com.openminis.app.ui.terminal

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.openminis.app.R

val JetBrainsMonoFontFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private var cachedRegular: Typeface? = null
private var cachedBold: Typeface? = null

fun jetBrainsMonoTypeface(context: Context, bold: Boolean = false): Typeface {
    val cached = if (bold) cachedBold else cachedRegular
    if (cached != null) return cached
    val resId = if (bold) R.font.jetbrains_mono_bold else R.font.jetbrains_mono_regular
    val tf = ResourcesCompat.getFont(context, resId) ?: Typeface.MONOSPACE
    if (bold) cachedBold = tf else cachedRegular = tf
    return tf
}

@Composable
fun rememberJetBrainsMonoTypeface(bold: Boolean = false): Typeface {
    val context = LocalContext.current
    return remember(bold) { jetBrainsMonoTypeface(context, bold) }
}
