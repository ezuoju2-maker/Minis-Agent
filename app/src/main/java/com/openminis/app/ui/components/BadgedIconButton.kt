package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun BadgedIconButton(
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    if (badge == null) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { icon() }
        return
    }
    BadgedBox(
        badge = { Box(Modifier.offset(x = -ANCHOR_INSET, y = ANCHOR_INSET)) { badge() } },
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, enabled = enabled) { icon() }
    }
}

internal val ANCHOR_INSET: Dp = 12.dp
