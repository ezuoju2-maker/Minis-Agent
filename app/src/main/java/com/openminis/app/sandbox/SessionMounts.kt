package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

object SessionMounts {
    private const val TAG = "SessionMounts"

    val SESSION_SUBDIRS = listOf("attachments", "offloads", "workspace", "browser")

    val GLOBAL_SUBDIRS = listOf("memory", "skills", "shared", "mcp-servers")

    const val ARGV_WARN_BYTES = 64 * 1024

    data class Built(
        val mounts: Map<String, String>,
        val skipped: List<String>,
    )

    fun sessionDir(filesDir: File, sessionId: String, subdir: String): File =
        File(filesDir, "minis-sessions/$sessionId/$subdir")

    fun build(
        filesDir: File,
        sessionId: String?,
        externalMounts: List<Pair<String, String>> = emptyList(),
    ): Built {
        val candidates = linkedMapOf<String, File>()
        if (sessionId != null) {
            for (sub in SESSION_SUBDIRS) candidates["/var/minis/$sub"] = sessionDir(filesDir, sessionId, sub)
        }
        val globalBase = File(filesDir, "minis-global")
        for (sub in GLOBAL_SUBDIRS) candidates["/var/minis/$sub"] = File(globalBase, sub)

        val mounts = linkedMapOf<String, String>()
        val skipped = mutableListOf<String>()
        for ((linux, dir) in candidates) {
            if (ensureDir(dir)) mounts[linux] = dir.absolutePath else skipped += linux
        }
        for ((linux, host) in externalMounts) {
            mounts[linux] = host
            if (!File(host).isDirectory) Log.w(TAG, "external mount $linux -> $host is not visible as a directory")
        }
        if (skipped.isNotEmpty()) {
            Log.e(
                TAG,
                "session=${sessionId ?: "<global>"} skipped ${skipped.size} bind mount(s) whose host dir " +
                    "is missing or cannot be created: $skipped — the guest will see the rootfs placeholder there",
            )
        }
        return Built(mounts, skipped)
    }

    fun forContext(context: Context, sessionId: String?): Built =
        build(context.filesDir, sessionId, externalMountsSnapshot())

    fun toProotArgs(mounts: Map<String, String>): List<String> {
        val args = ArrayList<String>(mounts.size * 2)
        var bytes = 0
        for ((linux, host) in mounts) {
            val spec = "$host:$linux"
            args += "-b"
            args += spec
            bytes += spec.length + 4
        }
        if (bytes > ARGV_WARN_BYTES) {
            Log.w(TAG, "bind-mount argv is $bytes bytes (> $ARGV_WARN_BYTES) across ${mounts.size} mounts")
        }
        return args
    }

    internal fun ensureDir(dir: File): Boolean {
        if (dir.isDirectory) return true
        dir.mkdirs()
        return dir.isDirectory
    }

    private fun externalMountsSnapshot(): List<Pair<String, String>> =
        PRootKernel.mountedFoldersStore?.entries?.value.orEmpty().mapNotNull { entry ->
            val host = entry.resolvedHostPath ?: return@mapNotNull null
            "/var/minis/mounts/${entry.name}" to host
        }
}
