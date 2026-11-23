package com.openminis.app.data.session

import java.io.File

class SessionStorage(private val filesDir: File) {

    val sessionsRoot: File get() = File(filesDir, SESSIONS_DIR)
    val mediaRoot: File get() = File(filesDir, MEDIA_DIR)

    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    fun mediaDirs(sessionId: String): List<File> {
        if (!mediaRoot.exists()) return emptyList()
        return mediaRoot.walkTopDown()
            .filter { it.isDirectory && it.name == sessionId && it != mediaRoot }
            .toList()
    }

    fun minisSize(sessionId: String): Long = directorySize(sessionDir(sessionId))

    fun mediaSize(sessionId: String): Long = mediaDirs(sessionId).sumOf { directorySize(it) }

    fun mediaSizesBySession(sessionIds: Set<String>): Map<String, Long> {
        if (!mediaRoot.exists()) return emptyMap()
        val sizes = mutableMapOf<String, Long>()
        mediaRoot.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val sid = file.parentFile?.name ?: return@forEach
            if (sid in sessionIds) sizes[sid] = (sizes[sid] ?: 0L) + file.length()
        }
        return sizes
    }

    data class DeleteResult(val freed: Long, val failedPaths: List<String>) {
        val isComplete: Boolean get() = failedPaths.isEmpty()
    }

    fun deleteFiles(sessionId: String): Long = deleteFilesDetailed(sessionId).freed

    fun deleteFilesDetailed(sessionId: String): DeleteResult {
        var freed = 0L
        val failed = mutableListOf<String>()

        fun wipe(target: File) {
            if (!target.exists()) return
            val size = directorySize(target)
            restoreWritable(target)
            if (deleteTreeNoFollow(target) && !target.exists()) {
                freed += size
            } else {
                failed += target.absolutePath
                freed += size - directorySize(target)
            }
        }

        wipe(sessionDir(sessionId))
        for (m in mediaDirs(sessionId)) wipe(m)
        return DeleteResult(freed, failed)
    }

    private fun restoreWritable(root: File) {
        try {
            java.nio.file.Files.walkFileTree(
                root.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun preVisitDirectory(
                        dir: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        val f = dir.toFile()
                        if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (!attrs.isSymbolicLink) {
                            val f = file.toFile()
                            if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        } catch (_: Exception) {
        }
    }

    private fun deleteTreeNoFollow(root: File): Boolean {
        val rootPath = root.toPath()
        if (!java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        try {
            java.nio.file.Files.walkFileTree(
                rootPath,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        exc: java.io.IOException?,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(dir) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
        }
        return !java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    fun hasFiles(sessionId: String): Boolean =
        sessionDir(sessionId).exists() || mediaDirs(sessionId).isNotEmpty()

    companion object {
        const val SESSIONS_DIR = "minis-sessions"
        const val MEDIA_DIR = "media"

        fun directorySize(dir: File): Long {
            if (!dir.exists()) return 0L
            var total = 0L
            dir.walkTopDown()
                .onEnter { !isSymlink(it) }
                .forEach { f ->
                    if (!isSymlink(f) && f.isFile) total += f.length()
                }
            return total
        }

        private fun isSymlink(f: File): Boolean =
            try {
                java.nio.file.Files.isSymbolicLink(f.toPath())
            } catch (_: Exception) {
                false
            }
    }
}
