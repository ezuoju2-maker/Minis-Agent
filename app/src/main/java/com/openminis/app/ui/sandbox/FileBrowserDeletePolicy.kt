package com.openminis.app.ui.sandbox

import com.openminis.app.sandbox.SessionMounts
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal object FileBrowserDeletePolicy {
    enum class Action {
        DELETE_ITEM,

        DELETE_LINK_ONLY,

        REFUSE_MOUNT_POINT,
    }

    private val PSEUDO_FS_MOUNT_POINTS = listOf("/dev", "/proc", "/sys")

    val FIXED_MOUNT_POINTS: Set<String> =
        (PSEUDO_FS_MOUNT_POINTS +
            SessionMounts.GLOBAL_SUBDIRS.map { "/var/minis/$it" } +
            SessionMounts.SESSION_SUBDIRS.map { "/var/minis/$it" }).toSet()

    fun action(linuxPath: String?, isSymlink: Boolean): Action {
        if (linuxPath != null && isMountPoint(linuxPath)) return Action.REFUSE_MOUNT_POINT
        return if (isSymlink) Action.DELETE_LINK_ONLY else Action.DELETE_ITEM
    }

    fun isMountPoint(linuxPath: String): Boolean {
        val parts = linuxPath.split('/').filter { it.isNotEmpty() }
        if ("/" + parts.joinToString("/") in FIXED_MOUNT_POINTS) return true
        return parts.size == 4 && parts[0] == "var" && parts[1] == "minis" && parts[2] == "mounts"
    }

    fun rowLinuxPath(dirLinuxPath: String?, name: String): String? =
        dirLinuxPath?.let { "${it.trimEnd('/')}/$name" }

    fun delete(file: File, action: Action): Boolean {
        require(action != Action.REFUSE_MOUNT_POINT) { "mount points are not deleted" }
        val path = file.toPath()
        if (action == Action.DELETE_LINK_ONLY || Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }
        return deleteTreeNoFollow(path)
    }

    private fun deleteTreeNoFollow(root: Path): Boolean {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.delete(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
        }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }
}
