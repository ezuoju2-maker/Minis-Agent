package com.openminis.app.agent.guard

object PathAccessPolicy {

    enum class Zone(val label: String) {
        WORKSPACE("workspace"),
        SHARED("shared"),
        SKILLS("skills"),
        MEMORY("memory"),
        TEMP("temp"),
        HOME("home"),
        SYSTEM("system"),
        OTHER("other"),
    }

    data class Verdict(val zone: Zone, val blocked: Boolean, val reason: String = "") {
        val allowed: Boolean get() = !blocked
    }

    private val workspaceRoots = listOf("/var/minis/workspace", "/var/minis/uploads", "/var/minis/outputs")
    private val sharedRoots = listOf("/var/minis/shared")
    private val skillRoots = listOf("/var/minis/skills")
    private val memoryRoots = listOf("/var/minis/memory")

    private val systemRoots = listOf(
        "/proc", "/sys", "/dev", "/boot",
        "/system", "/vendor", "/data", "/odm", "/product",
        "/etc", "/bin", "/sbin", "/lib", "/lib64", "/usr/lib", "/usr/bin", "/usr/sbin",
        "/var/lib", "/var/run", "/var/log",
    )

    fun normalize(rawPath: String, rootfsHost: String? = null): String {
        var path = rawPath.trim()
        if (path.isEmpty()) return ""
        if (path.startsWith("file://")) path = path.removePrefix("file://")
        if (!rootfsHost.isNullOrEmpty() && path.startsWith(rootfsHost)) {
            path = path.removePrefix(rootfsHost)
        }
        if (path.startsWith("~")) path = "/root" + path.removePrefix("~")
        while (path.contains("//")) path = path.replace("//", "/")
        if (!path.startsWith("/")) path = "/$path"
        return path
    }

    fun classify(guestPath: String): Zone {
        val p = normalize(guestPath)
        val lowered = p.lowercase()
        return when {
            matchesRoot(lowered, workspaceRoots) -> Zone.WORKSPACE
            matchesRoot(lowered, sharedRoots) -> Zone.SHARED
            matchesRoot(lowered, skillRoots) -> Zone.SKILLS
            matchesRoot(lowered, memoryRoots) -> Zone.MEMORY
            lowered == "/tmp" || lowered.startsWith("/tmp/") -> Zone.TEMP
            lowered == "/root" || lowered.startsWith("/root/") -> Zone.HOME
            matchesRoot(lowered, systemRoots) -> Zone.SYSTEM
            else -> Zone.OTHER
        }
    }

    fun evaluate(rawPath: String, rootfsHost: String? = null): Verdict {
        val guest = normalize(rawPath, rootfsHost)
        if (guest.isEmpty()) return Verdict(Zone.OTHER, blocked = false)
        val zone = classify(guest)
        return when (zone) {
            Zone.SYSTEM -> Verdict(
                zone = zone,
                blocked = true,
                reason = "Blocked by the file access guard: $guest is a protected system path inside the " +
                    "sandbox. Write into /var/minis/workspace, /var/minis/shared, /tmp or /root instead; " +
                    "system directories are changed through package managers, not by editing files.",
            )

            else -> Verdict(zone, blocked = false)
        }
    }

    fun writableRoots(): String = (workspaceRoots + sharedRoots + skillRoots + memoryRoots + listOf("/tmp", "/root"))
        .joinToString(", ")

    private fun matchesRoot(lowered: String, roots: List<String>): Boolean =
        roots.any { lowered == it || lowered.startsWith("$it/") }
}
