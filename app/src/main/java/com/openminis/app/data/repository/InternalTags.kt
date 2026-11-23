package com.openminis.app.data.repository

internal object InternalTags {

    private val SYSTEM_REMINDER = Regex(
        """<system-reminder\b[^>]*>.*?(?:</system-reminder\s*>|\z)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ATTACHED_FILES = Regex(
        """<user-attached-files\b[^>]*>.*?(?:</user-attached-files\s*>|\z)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val STRAY_CLOSERS = Regex(
        """</(?:system-reminder|user-attached-files|scheduled_task)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ATTACHMENT_MARKERS = listOf(
        Regex("""\[attached [A-Za-z]+:[^\]]*]"""),
        Regex("""\[image omitted to save context[^\]]*]"""),
    )
    private val SCHEDULED_OPENER = Regex("""^\s*<scheduled_task\b""", RegexOption.IGNORE_CASE)

    fun stripSystemReminders(raw: String): String =
        SYSTEM_REMINDER.replace(raw, "").trim()

    fun stripForPreview(raw: String): String {
        var s = SYSTEM_REMINDER.replace(raw, "")
        s = ATTACHED_FILES.replace(s, "")
        s = STRAY_CLOSERS.replace(s, "")
        if (s.contains('[')) for (re in ATTACHMENT_MARKERS) s = re.replace(s, "")
        return s.trim()
    }

    fun scheduledPreviewLine(text: String, noun: String, clean: (String) -> String): String? {
        val bare = stripSystemReminders(text)
        if (!SCHEDULED_OPENER.containsMatchIn(bare)) return null
        val marker = com.openminis.app.scheduled.ScheduledTaskMarker.parse(bare)
            ?: return noun
        val name = marker.label.trim().ifEmpty { clean(marker.prompt) }.trim()
        return if (name.isEmpty()) noun else "$noun · $name"
    }
}
