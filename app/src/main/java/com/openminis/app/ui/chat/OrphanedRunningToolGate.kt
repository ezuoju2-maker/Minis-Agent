package com.openminis.app.ui.chat

internal object OrphanedRunningToolGate {

    fun resolve(
        status: ToolBlockStatus?,
        isLiveRun: Boolean,
        jobIsAlive: Boolean,
    ): ToolBlockStatus? {
        if (status == null) return null
        if (!isSpinner(status)) return status
        if (isLiveRun || jobIsAlive) return status
        return ToolBlockStatus.TIMEOUT
    }

    fun isSpinner(status: ToolBlockStatus?): Boolean =
        status == ToolBlockStatus.RUNNING ||
            status == ToolBlockStatus.STREAMING ||
            status == ToolBlockStatus.PENDING

    fun overrideMayApply(
        overrideStatus: ToolBlockStatus?,
        isLiveRun: Boolean,
        jobIsAlive: Boolean,
    ): Boolean {
        if (!isSpinner(overrideStatus)) return true
        return isLiveRun || jobIsAlive
    }
}
