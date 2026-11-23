package com.openminis.app.ui.chat

object StreamFollowDeadband {

    const val SHRINK_DEADBAND_PX = 24f

    const val SHRINK_SETTLE_MS = 250L

    fun shouldSuppress(
        current: Float,
        previous: Float,
        msSinceGrowth: Long,
    ): Boolean {
        val delta = current - previous
        if (delta >= 0f) return false
        if (-delta >= SHRINK_DEADBAND_PX) return false
        if (msSinceGrowth >= SHRINK_SETTLE_MS) return false
        return true
    }
}
