package com.openminis.app.ui.chat

import org.json.JSONObject

object ToolTitleReminder {
    const val MARKER = "[minis:tool-title-reminder]"

    const val TEXT = "<system-reminder>$MARKER This is a soft reminder. " +
        "This tool call has no `tool_title`; please include a short one next time.</system-reminder>"

    fun isMissingTitle(args: JSONObject): Boolean {
        val v = args.opt("tool_title") as? String ?: return true
        return v.isBlank()
    }

    fun declaresTitle(parameterNames: Collection<String>): Boolean = "tool_title" in parameterNames

    fun appending(content: String): String = if (content.isEmpty()) TEXT else content + "\n\n" + TEXT

    private val displayRe = Regex("""\n*<system-reminder>\[minis:tool-title-reminder\][\s\S]*?</system-reminder>""")

    fun stripForDisplay(text: String): String =
        if (!text.contains(MARKER)) text else displayRe.replace(text, "")
}
