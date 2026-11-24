package com.openminis.app.skills.remote

data class RemoteSkillEntry(
    val id: String,
    val version: String,
    val url: String,
    val sha256: String,
    val description: String = "",
    val sizeBytes: Long = 0,
)

sealed class InstallPlan {
    data class Install(val entry: RemoteSkillEntry, val replacedVersion: String?) : InstallPlan()
    data class Skip(val reason: String) : InstallPlan()
    data class Reject(val reason: String) : InstallPlan()
}

class SkillRegistry(
    private val allowedHosts: Set<String> = DEFAULT_HOSTS,
    private val maxEntryBytes: Long = 32L * 1024 * 1024,
) {

    fun plan(entry: RemoteSkillEntry, installedVersion: String?): InstallPlan {
        if (!ID_RE.matches(entry.id)) return InstallPlan.Reject("invalid skill id: ${entry.id}")
        if (!entry.url.startsWith("https://")) return InstallPlan.Reject("insecure url for ${entry.id}")
        val host = hostOf(entry.url)
        if (host !in allowedHosts) return InstallPlan.Reject("untrusted host for ${entry.id}: $host")
        if (!SHA_RE.matches(entry.sha256)) return InstallPlan.Reject("bad checksum format for ${entry.id}")
        if (entry.sizeBytes > maxEntryBytes) return InstallPlan.Reject("skill too large: ${entry.id}")
        if (!VERSION_RE.matches(entry.version)) return InstallPlan.Reject("bad version for ${entry.id}: ${entry.version}")
        if (installedVersion != null) {
            val cmp = compareVersions(entry.version, installedVersion)
            if (cmp == 0) return InstallPlan.Skip("${entry.id} up to date at ${entry.version}")
            if (cmp < 0) return InstallPlan.Reject("refuse downgrade ${entry.id}: ${installedVersion} -> ${entry.version}")
        }
        return InstallPlan.Install(entry, installedVersion)
    }

    fun verify(bytes: ByteArray, expectedHex: String): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.contentEquals(hexToBytes(expectedHex))
    }

    fun compareVersions(a: String, b: String): Int {
        val pa = normalize(a)
        val pb = normalize(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun normalize(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.', '-', '_')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").substringBefore('/').substringBefore('?')
            .substringBefore(':').lowercase()

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    companion object {
        private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{1,63}$")
        private val SHA_RE = Regex("^[a-f0-9]{64}$")
        private val VERSION_RE = Regex("^v?[0-9]+(\\.[0-9]+){0,3}(-[a-z0-9.]{1,16})?$")
        val DEFAULT_HOSTS: Set<String> = setOf(
            "raw.githubusercontent.com",
            "github.com",
            "objects.githubusercontent.com",
        )
    }
}
