package com.openminis.app.agent.userask

import android.content.Context
import com.openminis.app.tools.AgentToolSwitch

object UserAskSettings {
    fun isEnabled(context: Context): Boolean = AgentToolSwitch.USER_ASK.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = AgentToolSwitch.USER_ASK.setEnabled(context, enabled)

    fun timeoutMinutes(context: Context): Int = UserAskSettingsSpec.clampTimeout(
        AgentToolSwitch.prefs(context).getInt(UserAskSettingsSpec.TIMEOUT_MINUTES_KEY, UserAskSettingsSpec.DEFAULT_TIMEOUT_MINUTES),
    )

    fun setTimeoutMinutes(context: Context, minutes: Int) {
        AgentToolSwitch.prefs(context).edit()
            .putInt(UserAskSettingsSpec.TIMEOUT_MINUTES_KEY, UserAskSettingsSpec.clampTimeout(minutes)).apply()
    }

    fun notifyInBackground(context: Context): Boolean =
        AgentToolSwitch.prefs(context).getBoolean(
            UserAskSettingsSpec.NOTIFY_IN_BACKGROUND_KEY, UserAskSettingsSpec.DEFAULT_NOTIFY_IN_BACKGROUND,
        )

    fun setNotifyInBackground(context: Context, on: Boolean) {
        AgentToolSwitch.prefs(context).edit().putBoolean(UserAskSettingsSpec.NOTIFY_IN_BACKGROUND_KEY, on).apply()
    }
}
