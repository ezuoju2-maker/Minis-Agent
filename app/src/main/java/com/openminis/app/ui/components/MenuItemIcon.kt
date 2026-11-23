package com.openminis.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Composable
fun MenuItemIcon(icon: ImageVector, tint: Color = LocalContentColor.current) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(MENU_ICON_SIZE))
}

private val MENU_ICON_SIZE = 20.dp

object MinisIcons {
    val SquareAndPencil: ImageVector by lazy {
        ImageVector.Builder(
            name = "SquareAndPencil",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.Black)
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(11f, 4f)
                horizontalLineTo(6f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 4f, y1 = 6f)
                verticalLineTo(18f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 6f, y1 = 20f)
                horizontalLineTo(18f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20f, y1 = 18f)
                verticalLineTo(13f)
            }
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(18.5f, 2.5f)
                arcTo(2.12f, 2.12f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21.5f, y1 = 5.5f)
                lineTo(12f, 15f)
                lineTo(8f, 16f)
                lineTo(9f, 12f)
                close()
            }
        }.build()
    }
}
