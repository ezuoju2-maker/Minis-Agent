package com.openminis.app.ui.chat

object StopConfirmPolicy {

    const val SETTINGS_KEY = "chat.confirmBeforeStopping"

    const val DEFAULT_ENABLED = false

    enum class TapAction {
        CHOOSE_SCOPE,

        CONFIRM_STOP,

        STOP_NOW,
    }

    fun onTap(hasBackground: Boolean, confirmEnabled: Boolean): TapAction = when {
        hasBackground -> TapAction.CHOOSE_SCOPE
        confirmEnabled -> TapAction.CONFIRM_STOP
        else -> TapAction.STOP_NOW
    }

    fun confirmStillApplies(turnAtOpen: Long?, turnNow: Long?, isBusy: Boolean): Boolean =
        isBusy && turnAtOpen == turnNow
}
