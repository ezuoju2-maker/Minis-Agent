package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

object ChatNestedScrollFollow {

    const val HOLD_MS = 300L

    fun isChildUserScroll(sourceIsUserInput: Boolean, deltaY: Float, listIsDragged: Boolean): Boolean =
        sourceIsUserInput && deltaY != 0f && !listIsDragged

    fun childScrollActive(gestureOpen: Boolean, msSinceLastDelta: Long): Boolean =
        gestureOpen && msSinceLastDelta in 0 until HOLD_MS

    fun followMaySnap(userScrolledAway: Boolean, listScrollInProgress: Boolean, childScrollActive: Boolean): Boolean =
        !userScrolledAway && !listScrollInProgress && !childScrollActive

    fun scrolledAwayAfterGesture(nearBottom: Boolean): Boolean = !nearBottom
}

object StopFlingAtEdgeConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset.Zero else available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}
