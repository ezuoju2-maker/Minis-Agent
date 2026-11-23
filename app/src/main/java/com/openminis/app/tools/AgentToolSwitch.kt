package com.openminis.app.tools

import android.content.Context

enum class AgentToolSwitch(val key: String, val defaultValue: Boolean) {
    BROWSER("agent.tools.browser.enabled", true),

    AGENTS("agent.tools.agents.enabled", true),

    USER_ASK(com.openminis.app.agent.userask.UserAskSettingsSpec.ENABLED_KEY, com.openminis.app.agent.userask.UserAskSettingsSpec.DEFAULT_ENABLED),
    ;

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(key, defaultValue)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(key, enabled).apply()
    }

    companion object {
        const val PREFS = "agent_settings"

        internal fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun governing(toolName: String): AgentToolSwitch? = when (toolName) {
            "browser_use" -> BROWSER
            "subagent_task", "delegate_task", "agent_status" -> AGENTS
            com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> USER_ASK
            else -> null
        }

        fun isToolEnabled(context: Context, toolName: String): Boolean =
            governing(toolName)?.isEnabled(context) ?: true


        private const val MIGRATION_KEY = "agent.tools.granular.v1.migrated"

        fun migrateLegacyIfNeeded(context: Context) {
            val p = prefs(context)
            if (p.getBoolean(MIGRATION_KEY, false)) return
            val legacyKey = com.openminis.app.agent.jobs.AgentSettings.KEY_ENABLED
            val editor = p.edit()
            if (!p.contains(AGENTS.key) && p.contains(legacyKey)) {
                editor.putBoolean(AGENTS.key, p.getBoolean(legacyKey, true))
            }
            editor.putBoolean(MIGRATION_KEY, true).commit()
        }
    }
}
