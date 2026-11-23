package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

internal object ProviderImportZip {

    private const val TAG = "ProviderImportZip"
    private val SUPPORTED_EXTS = setOf("json", "txt")

    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun importFromZip(
        context: Context,
        uri: Uri,
        onImportSingle: (String) -> String?,
        onExtractFailed: () -> Unit,
        onNoSupported: () -> Unit,
        onSummary: (ok: Int, total: Int) -> Unit,
    ) {
        val stagingRoot = File(context.cacheDir, "import-staging")
        if (!stagingRoot.exists()) stagingRoot.mkdirs()
        val stagingDir = File(stagingRoot, UUID.randomUUID().toString())
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
            onExtractFailed()
            return
        }
        val rootCanonical = stagingDir.canonicalPath
        val rootPrefix = rootCanonical + File.separator

        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                onExtractFailed()
                return
            }
            try {
                ZipInputStream(input.buffered()).use { zin ->
                    while (true) {
                        val entry = zin.nextEntry ?: break
                        try {
                            if (entry.isDirectory) continue
                            val rawName = entry.name
                            if (shouldSkipEntry(rawName)) continue

                            val target = File(stagingDir, rawName)
                            val canonical = target.canonicalPath
                            if (canonical != rootCanonical && !canonical.startsWith(rootPrefix)) {
                                Log.w(TAG, "rejected zip-slip entry: $rawName")
                                continue
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zin.copyTo(out) }
                        } finally {
                            zin.closeEntry()
                        }
                    }
                }
            } catch (e: ZipException) {
                Log.w(TAG, "zip extract failed", e)
                onExtractFailed()
                return
            } catch (e: IOException) {
                Log.w(TAG, "io while extracting zip", e)
                onExtractFailed()
                return
            }

            val candidates = mutableListOf<File>()
            collectSupportedFiles(stagingDir, candidates)
            if (candidates.isEmpty()) {
                onNoSupported()
                return
            }

            var ok = 0
            for (file in candidates) {
                try {
                    val content = file.readText()
                    val label = onImportSingle(content)
                    if (label != null) ok += 1
                } catch (e: Exception) {
                    Log.w(TAG, "skip import for ${file.name}: ${e.message}")
                }
            }
            onSummary(ok, candidates.size)
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private fun shouldSkipEntry(name: String): Boolean {
        if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) return true
        val leaf = name.substringAfterLast('/')
        if (leaf.isEmpty()) return true
        if (leaf == ".DS_Store") return true
        if (leaf.startsWith(".")) return true
        return false
    }

    private fun collectSupportedFiles(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectSupportedFiles(child, out)
            } else {
                val ext = child.extension.lowercase()
                if (ext in SUPPORTED_EXTS) out += child
            }
        }
    }
}
