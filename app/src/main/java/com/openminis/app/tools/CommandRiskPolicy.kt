package com.openminis.app.tools

/**
 * [v1.62-command-guard] Shell command risk policy.
 *
 * The shell is the agent's most powerful tool and the one place a bad guess is
 * irreversible. Best-in-class agents (Claude Code's permission modes, Cursor's
 * command approvals, Cline's terminal confirmation) put a deterministic
 * classifier in front of execution rather than relying on the model's judgment.
 * This app had no such layer: the prompt asked the model to be careful, which
 * is not a control.
 *
 * Scope is deliberately narrow, because a guard that cries wolf gets worked
 * around:
 *   - BLOCK only commands that destroy the sandbox rootfs or the user's data
 *     wholesale (`rm -rf /`, `rm -rf /var/minis`, `mkfs`, `dd` onto a device,
 *     fork bombs, `find / -delete`). Those cannot be justified by a legitimate
 *     agent task, and inside PRoot they brick the environment.
 *   - WARN for commands that are legitimate but data-destructive or execute
 *     remote code (`git reset --hard`, `git clean -fdx`, `curl | sh`,
 *     `chmod -R 777`, broad `pkill -9`). They still run; the result carries a
 *     notice so the transcript shows why it was dangerous.
 *   - SAFE otherwise. Normal `rm -rf build/` and `rm -rf node_modules` stay
 *     silent — that is everyday agent work.
 *
 * Pure: no IO, no Android. Deterministic for tests.
 */
object CommandRiskPolicy {

    enum class Level { SAFE, WARN, BLOCK }

    data class Assessment(
        val level: Level,
        val reasons: List<String>,
        /** Model-facing explanation of what to do instead. */
        val guidance: String? = null,
    ) {
        val blocked: Boolean get() = level == Level.BLOCK
    }

    /** Paths whose recursive removal destroys the environment, not a build artifact. */
    private val PROTECTED_ROOTS = setOf(
        "/", "/*", "~", "~/", "/*/", "/var", "/var/minis", "/var/minis/",
        "/var/minis/workspace", "/var/minis/workspace/", "/var/minis/shared",
        "/var/minis/attachments", "/var/minis/memory", "/var/minis/mounts",
        "/root", "/root/", "/home", "/home/", "/etc", "/etc/", "/usr", "/usr/",
        "/lib", "/lib/", "/lib64", "/bin", "/bin/", "/sbin", "/sbin/",
        "/opt", "/opt/", "/system", "/system/", "/data", "/data/", "/sdcard", "/storage",
    )

    private const val FORK_BOMB = ":(){ :|:& };:"
    private val CHAIN_TO_SHELL = Regex("""(curl|wget)\s[^|;&]*\|\s*(sudo\s+)?(sh|bash|zsh|dash)\b""")
    private val RESET_HARD = Regex("""\bgit\s+(?:-[-\w]+\s+)*reset\s+--hard\b""")
    private val GIT_CLEAN = Regex("""\bgit\s+clean\s+-[a-zA-Z]*[fx][a-zA-Z]*\b""")
    private val CHMOD_777 = Regex("""\bchmod\s+(?:-R\s+)?(?:0?777|a\+rwx)\s+/""")
    private val BROAD_KILL = Regex("""\b(?:kill|pkill)\s+-9\s+(?:-1|1|all)\b""")
    private val DD_DEVICE = Regex("""\bdd\b[^\n]*\bof=/dev/""")
    private val MKFS = Regex("""\bmkfs(\.\w+)?\b""")
    private val FIND_DELETE_ROOT = Regex("""\bfind\s+/\s[^\n]*-delete\b""")

    fun assess(command: String): Assessment {
        val raw = command.replace("\r\n", "\n")
        val compact = raw.trim()
        if (compact.isEmpty()) return Assessment(Level.SAFE, emptyList())

        // 1. Absolute hard blocks.
        if (compact.replace(" ", "").contains(FORK_BOMB.replace(" ", ""))) {
            return Assessment(
                Level.BLOCK,
                listOf("fork bomb"),
                "This command recursively spawns processes and would freeze the sandbox. " +
                    "It cannot accomplish any task; rewrite the work as ordinary commands.",
            )
        }
        if (MKFS.containsMatchIn(compact)) {
            return Assessment(
                Level.BLOCK,
                listOf("mkfs formats a filesystem"),
                "Formatting is never part of a task inside this sandbox and would destroy the Linux " +
                    "environment. If you need a blank disk image, create a file and use it as a loopback image instead.",
            )
        }
        if (DD_DEVICE.containsMatchIn(compact)) {
            return Assessment(
                Level.BLOCK,
                listOf("dd writing to a block device"),
                "`dd of=/dev/...` overwrites a device. Inside this sandbox there are no devices to write; " +
                    "write to a regular file under /var/minis/workspace instead.",
            )
        }
        if (FIND_DELETE_ROOT.containsMatchIn(compact)) {
            return Assessment(
                Level.BLOCK,
                listOf("find / -delete"),
                "Deleting from `/` recursively destroys the system tree. Target a specific directory " +
                    "under /var/minis/workspace.",
            )
        }

        // 2. rm with recursive+force against a protected root.
        val rmHit = rmAgainstProtectedRoot(compact)
        if (rmHit != null) {
            return Assessment(
                Level.BLOCK,
                listOf("rm -rf $rmHit"),
                "`$rmHit` is part of the environment (or the user's whole workspace) and removing it " +
                    "breaks the session with no way back. Delete specific paths inside it instead, " +
                    "e.g. `rm -rf /var/minis/workspace/build`.",
            )
        }

        // 3. Warnings — legitimate but destructive or remote-code execution.
        val warnings = ArrayList<String>()
        if (CHAIN_TO_SHELL.containsMatchIn(compact)) {
            warnings.add("pipes a downloaded script straight into a shell")
        }
        if (RESET_HARD.containsMatchIn(compact)) warnings.add("git reset --hard discards uncommitted work")
        if (GIT_CLEAN.containsMatchIn(compact)) warnings.add("git clean -fdx deletes untracked files")
        if (CHMOD_777.containsMatchIn(compact)) warnings.add("chmod 777 on a root path")
        if (BROAD_KILL.containsMatchIn(compact)) warnings.add("kills every process in the sandbox")
        if (warnings.isEmpty()) return Assessment(Level.SAFE, emptyList())

        return Assessment(
            Level.WARN,
            warnings,
            "This ran, but note: ${warnings.joinToString("; ")}. Prefer reversible steps " +
                "(commit or copy before resetting, fetch a file then inspect it before executing).",
        )
    }

    /**
     * Returns the protected target when the command is a recursive+force `rm`
     * against it, else null. Handles flags before/after the target, `--`,
     * quoted targets, trailing slashes and `$HOME`.
     */
    internal fun rmAgainstProtectedRoot(command: String): String? {
        // Only inspect simple rm invocations; anything more exotic still
        // contains the same tokens, so we split on shell separators too.
        var rootCwd = false
        for (segment in command.split(Regex("[;&|\\n]+"))) {
            val tokens = tokenize(segment)
            // "cd / && rm -rf etc" resolves a relative target against the
            // filesystem root, so it must be judged as "/etc".
            val cdIdx = tokens.indexOfFirst { it == "cd" }
            if (cdIdx >= 0) {
                val dest = tokens.getOrNull(cdIdx + 1)?.trim('\'', '"')
                rootCwd = dest == "/" || dest == "~" || dest == "\$HOME"
            }
            val rmIdx = tokens.indexOfFirst { it == "rm" || it.endsWith("/rm") }
            if (rmIdx < 0) continue
            var recursive = false
            var force = false
            val targets = ArrayList<String>()
            for (t in tokens.drop(rmIdx + 1)) {
                when {
                    t == "--recursive" -> recursive = true
                    t == "--force" -> force = true
                    t.startsWith("--") -> Unit
                    t.startsWith("-") && t.length > 1 -> {
                        if (t.contains('r') || t.contains('R')) recursive = true
                        if (t.contains('f')) force = true
                    }
                    else -> targets.add(t)
                }
            }
            if (!recursive || !force) continue
            for (t in targets) {
                val norm = normalizeTarget(t)
                if (norm in PROTECTED_ROOTS || norm.removeSuffix("/") in PROTECTED_ROOTS) return t
                if (rootCwd && !norm.startsWith("/") && "/$norm" in PROTECTED_ROOTS) return t
            }
        }
        return null
    }

    private fun normalizeTarget(t: String): String {
        var s = t.trim().trim('\'', '"')
        if (s == "\$HOME" || s == "\${HOME}") s = "~"
        while (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    /** Split a shell segment into words, keeping quoted strings together. */
    internal fun tokenize(segment: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                quote != null -> {
                    if (c == quote) quote = null else cur.append(c)
                }
                c == '\'' || c == '"' -> quote = c
                c == ' ' || c == '\t' -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
