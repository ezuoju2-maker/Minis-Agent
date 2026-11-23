package com.openminis.app.backup.remote

import com.openminis.app.logging.AppLogger

object RcloneBrowser {

    private const val TAG = "Rclone"

    data class Entry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val modified: Long? = null,
    )

    fun parseModTime(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        if (s.startsWith("0001-01-01")) return null
        for (pattern in TIME_PATTERNS) {
            runCatching {
                val f = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return f.parse(s)?.time
            }
        }
        return null
    }

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    fun listDirectories(remote: RcloneRemoteStore.Remote, path: String): List<Entry> {
        val resp = RcloneBridge.rpc(
            "operations/list",
            mapOf(
                "fs" to remote.fsSpec,
                "remote" to path,
                "opt" to mapOf("dirsOnly" to true),
            ),
        )
        val arr = resp.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optBoolean("IsDir")) return@mapNotNull null
            Entry(
                name = o.optString("Name"),
                path = o.optString("Path"),
                isDir = true,
                modified = parseModTime(o.optString("ModTime")),
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun createFolder(remote: RcloneRemoteStore.Remote, parentPath: String, name: String): String {
        val clean = name.trim().trim('/')
        require(clean.isNotEmpty()) { "Folder name is empty." }
        val full = if (parentPath.isEmpty()) clean else "${parentPath.trimEnd('/')}/$clean"
        RcloneBridge.rpc("operations/mkdir", mapOf("fs" to remote.fsSpec, "remote" to full))
        AppLogger.info(TAG, "[Rclone] created folder: $full on ${remote.name}")
        return full
    }

    fun testConnection(remote: RcloneRemoteStore.Remote) {
        RcloneBridge.rpc(
            "operations/list",
            mapOf("fs" to remote.fsSpec, "remote" to "", "opt" to mapOf("dirsOnly" to true)),
        )
    }
}
