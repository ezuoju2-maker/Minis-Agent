package com.openminis.app.sandbox

internal object ForegroundCommandGroup {

    const val SETSID = "/usr/bin/setsid"

    fun wrap(command: String, marker: String, setsidAvailable: Boolean = true): String {
        if (!setsidAvailable) {
            return "$command\necho \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n"
        }
        val quoted = command.replace("'", "'\\''")
        return buildString {
            append(SETSID)
            append(" sh -c 'echo \"__MINIS_PGID_")
            append(marker)
            append("_$(awk \"{print \\$5}\" /proc/self/stat)__\"; ")
            append(quoted)
            append("'\n")
            append("echo \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n")
        }
    }

    fun wrapForFreshProcess(command: String, statusPath: String): List<String> =
        listOf(SETSID, "/bin/sh", "-c", FRESH_RUNNER, "sh", command, statusPath)

    const val FRESH_RUNNER =
        "printf '%s' \"\$\$\" > \"\$2.pid\"; /bin/sh -c \"\$1\"; rc=\$?; printf '%s' \"\$rc\" > \"\$2\"; exit \$rc"

    fun pgidMarkerPrefix(marker: String): String = "__MINIS_PGID_${marker}_"

    fun parsePgid(text: String, marker: String): Int? {
        val prefix = pgidMarkerPrefix(marker)
        val start = text.indexOf(prefix)
        if (start < 0) return null
        val from = start + prefix.length
        val end = text.indexOf("__", from)
        if (end < 0) return null
        val pgid = text.substring(from, end).trim().toIntOrNull() ?: return null
        return if (pgid > 1) pgid else null
    }

    fun killGroupCommand(pgid: Int): String =
        "$SETSID sh -c 'kill -TERM -$pgid 2>/dev/null; sleep 1; kill -KILL -$pgid 2>/dev/null' >/dev/null 2>&1 &\n"

    fun shellIsReusableAfterTimeout(): Boolean = false
}
