package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

@Composable
fun rememberSheetScrollGuard(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val isFlingMomentum = source == NestedScrollSource.SideEffect
            return if (available.y > 0f && isFlingMomentum) {
                Offset(0f, available.y)
            } else {
                Offset.Zero
            }
        }
    }
}
