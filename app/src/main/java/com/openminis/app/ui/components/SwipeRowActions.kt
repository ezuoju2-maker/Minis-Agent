package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SwipeRowAction(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)

private enum class SwipeAnchor { Resting, Revealed }

@Composable
fun SwipeRowActions(
    actions: List<SwipeRowAction>,
    modifier: Modifier = Modifier,
    actionWidth: androidx.compose.ui.unit.Dp = 88.dp,
    rowColor: Color = SectionDesign.cardColor(),
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        Box(modifier = modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { (actionWidth * actions.size).toPx() }

    val state = remember(revealPx) {
        AnchoredDraggableState(
            initialValue = SwipeAnchor.Resting,
            anchors = DraggableAnchors {
                SwipeAnchor.Resting at 0f
                SwipeAnchor.Revealed at -revealPx
            },
        )
    }

    LaunchedEffect(state.settledValue) {
        if (state.settledValue == SwipeAnchor.Revealed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    var rowHeightPx by remember { mutableIntStateOf(0) }
    val rowHeight = with(density) { rowHeightPx.toDp() }

    Box(modifier = modifier.clipToBounds()) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .then(if (rowHeightPx > 0) Modifier.height(rowHeight) else Modifier),
            horizontalArrangement = Arrangement.End,
        ) {
            actions.forEach { action ->
                Column(
                    modifier = Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .background(action.containerColor)
                        .clickable {
                            scope.launch { state.animateTo(SwipeAnchor.Resting) }
                            action.onClick()
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint = action.contentColor,
                    )
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = action.contentColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeightPx = it.height }
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                .background(rowColor)
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                ),
        ) {
            content()
        }
    }
}
