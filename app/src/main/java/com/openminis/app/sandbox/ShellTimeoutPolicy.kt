package com.openminis.app.sandbox

object ShellTimeoutPolicy {
    const val DEFAULT_TIMEOUT_MS = 600_000L

    const val QUICK_TIMEOUT_MS = 60_000L

    const val NETWORK_TIMEOUT_MS = 180_000L

    const val INSTALL_TIMEOUT_MS = 600_000L

    const val BUILD_TIMEOUT_MS = 1_200_000L

    const val LONG_RUNNING_TIMEOUT_MS = 1_800_000L

    private val quickPrefixes = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "rg", "egrep", "fgrep",
        "sed", "awk", "tr", "cut", "uniq",
        "echo", "printf", "pwd", "which", "whoami", "id", "env", "date",
        "basename", "dirname", "file", "stat",
        "mkdir", "rmdir", "touch",
    )

    private val networkPrefixes = setOf(
        "ping", "dig", "nslookup", "host", "traceroute",
        "curl", "wget", "http",
        "ssh", "scp", "rsync",
    )

    private val installTokens = setOf(
        "apk", "apt", "apt-get", "yum", "dnf", "pacman", "brew",
        "pip", "pip3", "pipx",
        "npm", "pnpm", "yarn", "bun",
        "gem", "bundle",
        "go get", "go install",
    )

    private val buildPrefixes = setOf(
        "make", "gmake", "ninja", "cmake",
        "cargo", "rustc",
        "go build", "go test",
        "gradle", "gradlew", "mvn",
        "webpack", "vite", "rollup", "esbuild",
        "tsc", "swc", "babel",
    )

    private val longRunningPrefixes = setOf(
        "watch",
        "node --watch", "npm run dev", "npm run start", "npm start",
        "python -m http.server",
        "serve", "http-server",
    )

    private val recursiveFlag = Regex("(^|\\s)-[a-zA-Z]*[rR](\\s|$)")

    fun forCommand(command: String): Long {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return DEFAULT_TIMEOUT_MS
        val segments = splitSegments(trimmed.lowercase())
        if (segments.isEmpty()) return DEFAULT_TIMEOUT_MS
        var widest = 0L
        for (segment in segments) {
            val value = timeoutForSegment(segment)
            if (value > widest) widest = value
        }
        return if (widest > 0L) widest else DEFAULT_TIMEOUT_MS
    }

    private fun splitSegments(lower: String): List<String> =
        lower.replace("&&", "\n")
            .replace("||", "\n")
            .replace(";", "\n")
            .replace("|", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun timeoutForSegment(segment: String): Long {
        val firstToken = segment.substringBefore(' ')

        if (longRunningPrefixes.any { segment.startsWith(it) }) return LONG_RUNNING_TIMEOUT_MS
        if (buildPrefixes.any { segment.startsWith(it) }) return BUILD_TIMEOUT_MS
        if (installTokens.any { segment.startsWith(it) && segment.containsInstallSubcommand() }) {
            return INSTALL_TIMEOUT_MS
        }

        if (recursiveFlag.containsMatchIn(segment) || segment.contains("--recursive")) {
            return if (firstToken in networkPrefixes) NETWORK_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
        }
        if (firstToken in networkPrefixes) return NETWORK_TIMEOUT_MS
        if (firstToken in quickPrefixes) return QUICK_TIMEOUT_MS
        return DEFAULT_TIMEOUT_MS
    }

    private fun String.containsInstallSubcommand(): Boolean {
        val installVerbs = listOf("install", "add", "get", "update", "upgrade")
        return installVerbs.any { " " + it in this } || this.startsWith("apk ") || this.startsWith("pip install")
    }
}
