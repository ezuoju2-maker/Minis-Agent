package com.openminis.app.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

fun Modifier.expandToggleClickable(
    onClickLabel: String? = null,
    role: Role? = null,
    pressedShape: Shape = RoundedCornerShape(6.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = PRESSED_TINT_ALPHA) else Color.Transparent
    val base = this.background(tint, pressedShape)
    if (onLongClick == null) {
        base.clickable(
            interactionSource = interaction,
            indication = null,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
    } else {
        base.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}

private const val PRESSED_TINT_ALPHA = 0.06f
