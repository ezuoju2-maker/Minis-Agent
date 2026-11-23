package com.openminis.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.openminis.app.data.SpeedTier

object SpeedTierIcons {
    private fun bolt(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = if (filled) 0.8f else 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.5f, 2.5f)
                lineTo(4.5f, 13.5f)
                lineTo(11f, 13.5f)
                lineTo(10.5f, 21.5f)
                lineTo(19.5f, 10.5f)
                lineTo(13f, 10.5f)
                close()
            }
        }.build()

    const val BOLT_INK_FRACTION = (21.5f - 2.5f + 1.8f) / 24f

    fun boltBoxForInk(inkHeightPx: Float): Float = inkHeightPx / BOLT_INK_FRACTION

    val BoltOutline: ImageVector by lazy { bolt("SpeedBoltOutline", filled = false) }

    val BoltFill: ImageVector by lazy { bolt("SpeedBoltFill", filled = true) }

    val Speedometer: ImageVector get() = Icons.Outlined.Speed

    fun forTier(tier: SpeedTier): ImageVector = when (tier) {
        SpeedTier.STANDARD -> Speedometer
        SpeedTier.FAST -> BoltOutline
        SpeedTier.ULTRAFAST -> BoltFill
    }
}
