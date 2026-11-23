package com.openminis.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.ui.theme.ChatColors

@Composable
fun MinisMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = RoundedCornerShape(20.dp),
    containerColor: Color = if (ChatColors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White,
    tonalElevation: Dp = 3.dp,
    shadowElevation: Dp = 6.dp,
    border: BorderStroke? = if (ChatColors.isDark) {
        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.05f))
    },
    minWidth: Dp = 180.dp,
    alignEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    val marginXPx = with(density) { MinisMenuPosition.HORIZONTAL_MARGIN.roundToPx() }
    val marginYPx = with(density) { MinisMenuPosition.VERTICAL_MARGIN.roundToPx() }
    Popup(
        onDismissRequest = onDismissRequest,
        properties = properties,
        popupPositionProvider = remember(offsetXPx, offsetYPx, alignEnd, marginXPx, marginYPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = MinisMenuPosition.compute(
                    anchorBounds, windowSize, popupContentSize,
                    alignEnd, offsetXPx, offsetYPx, marginXPx, marginYPx,
                )
            }
        },
    ) {
        BoxWithConstraints {
            val maxMenuHeight = (maxHeight - MinisMenuPosition.VERTICAL_MARGIN * 2).coerceAtLeast(0.dp)
            val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
            val origin = if (alignEnd) {
                androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            } else {
                androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
            AnimatedVisibility(
                visibleState = visibleState,
                enter = scaleIn(
                    animationSpec = tween(200),
                    initialScale = 0.85f,
                    transformOrigin = origin,
                ) + fadeIn(animationSpec = tween(200)),
                exit = scaleOut(animationSpec = tween(120)) + fadeOut(animationSpec = tween(120)),
            ) {
                Surface(
                    modifier = modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = minWidth, max = 280.dp)
                        .heightIn(max = maxMenuHeight),
                    shape = shape,
                    color = containerColor,
                    tonalElevation = tonalElevation,
                    shadowElevation = shadowElevation.coerceAtMost(MinisMenuPosition.MAX_POPUP_SHADOW_ELEVATION),
                    border = border,
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(scrollState),
                        content = content,
                    )
                }
            }
        }
    }
}

internal object MinisMenuPosition {
    val HORIZONTAL_MARGIN: Dp = 8.dp

    val VERTICAL_MARGIN: Dp = 8.dp

    val MAX_POPUP_SHADOW_ELEVATION: Dp = 8.dp

    fun compute(
        anchorBounds: IntRect,
        windowSize: IntSize,
        popupSize: IntSize,
        alignEnd: Boolean,
        offsetX: Int,
        offsetY: Int,
        marginX: Int,
        marginY: Int,
    ): IntOffset {
        val rawX = if (alignEnd) anchorBounds.right - popupSize.width + offsetX else anchorBounds.left + offsetX
        val x = clampOrCenter(rawX, popupSize.width, windowSize.width, marginX)
        val belowY = anchorBounds.bottom + offsetY
        val aboveY = anchorBounds.top - popupSize.height - offsetY
        val fitsBelow = belowY + popupSize.height <= windowSize.height - marginY
        val y = if (fitsBelow || aboveY < marginY) belowY else aboveY
        return IntOffset(x, clampOrCenter(y, popupSize.height, windowSize.height, marginY))
    }

    internal fun clampOrCenter(value: Int, size: Int, window: Int, margin: Int): Int {
        val max = window - margin - size
        return if (max < margin) (window - size) / 2 else value.coerceIn(margin, max)
    }
}

@Composable
fun MinisMenuDivider(modifier: Modifier = Modifier) {
    val tint = if (ChatColors.isDark) Color.White else Color.Black
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        thickness = 1.dp,
        color = tint.copy(alpha = 0.12f),
    )
}

object MinisMenuDefaults {
    val ItemPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

    @Composable
    fun itemColors(
        textColor: Color = MaterialTheme.colorScheme.onSurface,
        leadingIconColor: Color = MaterialTheme.colorScheme.primary,
        trailingIconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    ): MenuItemColors = MenuDefaults.itemColors(
        textColor = textColor,
        leadingIconColor = leadingIconColor,
        trailingIconColor = trailingIconColor,
    )

    @Composable
    fun destructiveItemColors(): MenuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.error,
        leadingIconColor = MaterialTheme.colorScheme.error,
        trailingIconColor = MaterialTheme.colorScheme.error,
    )
}
