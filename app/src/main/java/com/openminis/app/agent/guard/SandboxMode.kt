package com.openminis.app.agent.guard

import android.content.Context

enum class SandboxMode(val id: String) {
    READ_ONLY("read_only"),
    WORKSPACE_WRITE("workspace_write"),
    FULL("full"),
    ;

    fun label(): String = when (this) {
        READ_ONLY -> "read-only (no writes at all)"
        WORKSPACE_WRITE -> "workspace-write (writes outside /var/minis, /tmp and /root are refused)"
        FULL -> "full access (only the per-command risk rules apply)"
    }

    companion object {
        const val PREFS = "agent_settings"
        const val KEY = "agent.sandbox.mode"
        const val DEFAULT = FULL

        fun parse(raw: String?): SandboxMode? = when (raw?.trim()?.lowercase()?.replace('-', '_')) {
            "read_only", "readonly", "ro" -> READ_ONLY
            "workspace_write", "workspace", "ws" -> WORKSPACE_WRITE
            "full", "full_access", "danger_full_access", "off" -> FULL
            else -> null
        }

        fun get(context: Context): SandboxMode {
            val stored = prefs(context).getString(KEY, null)
            return parse(stored) ?: DEFAULT
        }

        fun set(context: Context, mode: SandboxMode) {
            prefs(context).edit().putString(KEY, mode.id).apply()
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
