package com.openminis.app.agent.guard

object SandboxGuard {

    data class Verdict(val blocked: Boolean, val reason: String = "") {
        val allowed: Boolean get() = !blocked
    }

    private val WRITE_WORDS = listOf(
        Regex("""(^|[^|&;]\s)>\s*[^\s|&;]"""),
        Regex(""">>\s*[^\s|&;]"""),
        Regex("""\btee\b"""),
        Regex("""\brm\b"""),
        Regex("""\brmdir\b"""),
        Regex("""\bmv\b"""),
        Regex("""\bcp\b"""),
        Regex("""\bmkdir\b"""),
        Regex("""\btouch\b"""),
        Regex("""\btruncate\b"""),
        Regex("""\bchmod\b"""),
        Regex("""\bchown\b"""),
        Regex("""\bln\b"""),
        Regex("""\bsed\s+(?:-[a-zA-Z]+i|--in-place)\b"""),
        Regex("""\bdd\b""" ),
        Regex("""\binstall\b""" ),
        Regex("""\b(?:apt|apt-get|apk|yum|dnf)\s+(?:-\S+\s+)*(?:install|upgrade|remove|purge|add)\b"""),
        Regex("""\b(?:pip|pip3|pipx|npm|pnpm|yarn|gem|composer)\s+(?:-\S+\s+)*(?:install|add|remove|uninstall|update)\b"""),
        Regex("""\b(?:cargo|go)\s+(?:install|get)\b"""),
        Regex("""\bfind\b[^\n]*\s(?:-delete|-exec\s+(?:rm|shred|truncate|dd))""" ),
        Regex("""\bgit\s+(?:-\S+\s+)*(?:checkout|reset|clean|apply|merge|rebase|stash|commit)\b"""),
    )

    private val SYSTEM_ROOTS = listOf(
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/opt", "/boot",
        "/system", "/vendor", "/data", "/proc", "/sys", "/dev", "/var/lib", "/var/log", "/var/run",
    )

    private val ABSOLUTE_PATH = Regex("""(?:^|\s|=|"|')(/(?:[^\s"'`;|&<>()]+))""")

    fun isWriteCommand(command: String): Boolean = WRITE_WORDS.any { it.containsMatchIn(command) }

    fun assess(mode: SandboxMode, command: String): Verdict {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Verdict(false)
        if (mode == SandboxMode.FULL) return Verdict(false)
        if (mode == SandboxMode.READ_ONLY && isWriteCommand(cmd)) {
            return Verdict(
                true,
                "Refused: the session sandbox mode is read-only, so commands that write (redirection, cp/mv/rm, " +
                    "package or git writes) are not run. Read the files and report back, or ask the user to switch " +
                    "the mode to workspace-write.",
            )
        }
        if (mode == SandboxMode.WORKSPACE_WRITE && isWriteCommand(cmd)) {
            for (match in ABSOLUTE_PATH.findAll(cmd)) {
                val path = "/" + match.groupValues[1]
                if (!isWorkspacePath(path) && isSystemPath(path)) {
                    return Verdict(
                        true,
                        "Refused: the session sandbox mode is workspace-write, and this command writes to the " +
                            "protected path $path. Write under /var/minis/workspace (or /tmp, /root) instead; " +
                            "system directories need full access, which the user can enable deliberately.",
                    )
                }
            }
        }
        return Verdict(false)
    }

    fun isWorkspacePath(path: String): Boolean {
        val p = PathAccessPolicy.normalize(path)
        return p == "/tmp" || p.startsWith("/tmp/") ||
            p == "/root" || p.startsWith("/root/") ||
            p == "/var/minis" || p.startsWith("/var/minis/")
    }

    fun isSystemPath(path: String): Boolean {
        val p = PathAccessPolicy.normalize(path).lowercase()
        return SYSTEM_ROOTS.any { p == it || p.startsWith("$it/") }
    }
}
