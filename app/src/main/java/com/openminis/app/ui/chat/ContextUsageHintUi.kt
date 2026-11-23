package com.openminis.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember


internal object ContextUsageColors {

    @Composable
    fun textHighlight(tier: ContextUsage.Tier): Color = when (tier) {
        ContextUsage.Tier.NORMAL -> Color.Unspecified
        ContextUsage.Tier.WARNING -> Color(0xFF8A6A3A).copy(alpha = 0.55f)
        ContextUsage.Tier.CRITICAL -> Color(0xFF8E4B44).copy(alpha = 0.55f)
    }

    fun glow(tier: ContextUsage.Tier): Color? = when (tier) {
        ContextUsage.Tier.NORMAL -> null
        ContextUsage.Tier.WARNING -> Color(0xFFFFB300)
        ContextUsage.Tier.CRITICAL -> Color(0xFFE53935)
    }

    fun glowAlpha(tier: ContextUsage.Tier): Float = when (tier) {
        ContextUsage.Tier.NORMAL -> 0f
        ContextUsage.Tier.WARNING -> 0.11f
        ContextUsage.Tier.CRITICAL -> 0.10f
    }
}

@Composable
internal fun contextUsageAnnotatedText(hint: ContextUsageHint): AnnotatedString {
    val percent = hint.highlights.getOrNull(0) ?: ""
    val size = hint.highlights.getOrNull(1) ?: ""
    val context = stringResource(R.string.chat_context_usage_hint, percent, size)
    val full = hint.quotaText?.let { "$context · $it" } ?: context
    val highlight = ContextUsageColors.textHighlight(hint.tier)
    val quotaHighlight = ContextUsageColors.textHighlight(hint.quotaTier)
    return buildAnnotatedString {
        append(full)
        for (segment in hint.highlights) {
            if (segment.isEmpty()) continue
            val start = context.indexOf(segment)
            if (start < 0) continue
            addStyle(
                SpanStyle(color = highlight, fontWeight = FontWeight.Medium),
                start,
                start + segment.length,
            )
        }
        val q = hint.quotaHighlight
        if (!q.isNullOrEmpty() && hint.quotaTier != ContextUsage.Tier.NORMAL) {
            val start = full.indexOf(q, startIndex = context.length)
            if (start >= 0) {
                addStyle(SpanStyle(color = quotaHighlight, fontWeight = FontWeight.Medium), start, start + q.length)
            }
        }
    }
}

@Composable
internal fun Modifier.contextUsageGlow(
    tier: ContextUsage.Tier,
    cornerRadius: Dp = 22.dp,
    thickness: Dp = 22.dp,
    blurRadius: Dp = 16.dp,
    animate: Boolean = true,
): Modifier {
    val color = ContextUsageColors.glow(tier) ?: return this
    val base = ContextUsageColors.glowAlpha(tier)

    val alphaState: State<Float>? = if (tier == ContextUsage.Tier.CRITICAL && animate) {
        val tick = rememberDecorativeTick()
        remember(tick, base) {
            derivedStateOf {
                base * (0.7f + 0.6f * decorativePingPong(decorativePhase(tick.value, 5200)))
            }
        }
    } else {
        null
    }

    return this.drawWithContent {
        drawContent()
        val alpha = alphaState?.value ?: base
        val strokePx = thickness.toPx()
        val radiusPx = cornerRadius.toPx()
        val blurPx = blurRadius.toPx()
        if (alpha <= 0.001f || strokePx <= 0f) return@drawWithContent

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()

            val outline = android.graphics.Path().apply {
                addRoundRect(
                    0f, 0f, size.width, size.height,
                    radiusPx, radiusPx,
                    android.graphics.Path.Direction.CW,
                )
            }
            native.clipPath(outline)

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = strokePx
                this.color = android.graphics.Color.argb(
                    (alpha * 255f).toInt().coerceIn(0, 255),
                    (color.red * 255f).toInt(),
                    (color.green * 255f).toInt(),
                    (color.blue * 255f).toInt(),
                )
                maskFilter = android.graphics.BlurMaskFilter(
                    blurPx,
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            }
            native.drawPath(outline, paint)
            native.restoreToCount(save)
        }
    }
}
