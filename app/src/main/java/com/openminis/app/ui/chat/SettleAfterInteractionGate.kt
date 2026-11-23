package com.openminis.app.ui.chat

object SettleAfterInteractionGate {

    const val RECENT_DRAG_MS = 1500L

    fun shouldSettle(
        scrollInProgress: Boolean,
        userScrolledAway: Boolean,
        nearBottom: Boolean,
        streaming: Boolean,
        msSinceDrag: Long,
    ): Boolean {
        if (scrollInProgress) return false
        if (userScrolledAway) return false
        if (!nearBottom) return false
        if (!streaming) return false
        return msSinceDrag in 0..RECENT_DRAG_MS
    }
}
