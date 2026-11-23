package com.openminis.app.ui.chat

internal object SessionTitleRules {

    fun display(stored: String?, untitled: String): String = stored?.takeIf { it.isNotBlank() } ?: untitled

    fun autoTitleMayReplace(stored: String?, untitled: String): Boolean =
        stored.isNullOrBlank() || stored == untitled
}
