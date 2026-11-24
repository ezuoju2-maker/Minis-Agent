package com.openminis.app.tools

object CommandRiskPolicy {

    enum class Level { SAFE, WARN, BLOCK }

    data class Assessment(
        val level: Level,
        val reasons: List<String>,
        val guidance: String? = null,
    ) {
        val blocked: Boolean get() = level == Level.BLOCK
    }

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
    private val FIND_DESTRUCT_ROOT = Regex("""\bfind\s+/\s[^\n]*(?:-delete|-exec\s+(?:rm|shred|truncate|dd)|-ok\s+rm|\|\s*xargs\s+(?:sudo\s+)?rm)""")
    private val REDIRECT_PROTECTED = Regex(""">>?\s*(?:/etc/|/proc/|/sys/|/system/|/bin/|/sbin/|/usr/|/lib/|/lib64/|/root|/data/|/var/minis/(?:memory|attachments|mounts|shared))""")
    private val WIPE_ALL = setOf("*", "./*", ".", "./")

    fun assess(command: String): Assessment {
        val raw = command.replace("\r\n", "\n")
        val compact = raw.trim()
        if (compact.isEmpty()) return Assessment(Level.SAFE, emptyList())

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

        if (FIND_DESTRUCT_ROOT.containsMatchIn(compact)) {
            return Assessment(
                Level.BLOCK,
                listOf("find / with a destructive action"),
                "Running find from `/` together with -delete/-exec rm rewrites or destroys the system tree. " +
                    "Target a specific directory under /var/minis/workspace and list the matches first.",
            )
        }
        if (REDIRECT_PROTECTED.containsMatchIn(compact)) {
            return Assessment(
                Level.BLOCK,
                listOf("redirects output into a protected path"),
                "Writing over /etc, /usr, /bin or /var/minis state files breaks the environment. " +
                    "Write into /var/minis/workspace instead.",
            )
        }
        val rmEscape = relativeEscapeDepth(compact)
        if (rmEscape != null && rmEscape >= 2) {
            return Assessment(
                Level.BLOCK,
                listOf("recursive delete $rmEscape levels above the working directory"),
                "Climbing that far up leaves the workspace and removes parts of the sandbox itself. " +
                    "Delete specific paths inside /var/minis/workspace instead.",
            )
        }

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

        val warnings = ArrayList<String>()
        if (CHAIN_TO_SHELL.containsMatchIn(compact)) {
            warnings.add("pipes a downloaded script straight into a shell")
        }
        if (RESET_HARD.containsMatchIn(compact)) warnings.add("git reset --hard discards uncommitted work")
        if (GIT_CLEAN.containsMatchIn(compact)) warnings.add("git clean -fdx deletes untracked files")
        if (CHMOD_777.containsMatchIn(compact)) warnings.add("chmod 777 on a root path")
        if (BROAD_KILL.containsMatchIn(compact)) warnings.add("kills every process in the sandbox")
        if (rmEscape != null && rmEscape == 1) warnings.add("recursive delete one level above the working directory")
        if (wipeAll(compact)) warnings.add("deletes every entry in the current directory; target a specific path")
        if (warnings.isEmpty()) return Assessment(Level.SAFE, emptyList())

        return Assessment(
            Level.WARN,
            warnings,
            "This ran, but note: ${warnings.joinToString("; ")}. Prefer reversible steps " +
                "(commit or copy before resetting, fetch a file then inspect it before executing).",
        )
    }

    internal fun rmAgainstProtectedRoot(command: String): String? {
        var rootCwd = false
        for (segment in command.split(Regex("[;&|\\n]+"))) {
            val tokens = tokenize(segment)
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

    internal fun relativeEscapeDepth(command: String): Int? {
        for (segment in command.split(Regex("[;&|\\n]+"))) {
            val tokens = tokenize(segment)
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
                val norm = t.trim().trim('\'', '"')
                if (!norm.startsWith("..")) continue
                var depth = 0
                for (part in norm.split('/')) {
                    if (part == "..") depth++ else break
                }
                if (depth > 0) return depth
            }
        }
        return null
    }

    internal fun wipeAll(command: String): Boolean {
        for (segment in command.split(Regex("[;&|\\n]+"))) {
            val tokens = tokenize(segment)
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
            if (!recursive) continue
            for (t in targets) {
                if (t.trim().trim('\'', '"') in WIPE_ALL) return true
            }
        }
        return false
    }

    private fun normalizeTarget(t: String): String {
        var s = t.trim().trim('\'', '"')
        if (s == "\$HOME" || s == "\${HOME}") s = "~"
        while (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }

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
