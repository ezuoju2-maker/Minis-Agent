package com.openminis.app.agent.jobs

import android.content.Context

object AgentSettings {
    const val PREFS = "agent_settings"
    const val KEY_ENABLED = "agent.helpers.enabled"

    fun isEnabled(context: Context): Boolean =
        com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        com.openminis.app.tools.AgentToolSwitch.AGENTS.setEnabled(context, enabled)
    }
}
