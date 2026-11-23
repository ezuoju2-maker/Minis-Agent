package com.openminis.app

import java.io.File

object ProductionSources {

    private const val MAIN_ROOT = "src/main/java/com/openminis/app"

    fun mainRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, MAIN_ROOT)
            if (direct.isDirectory) return direct
            val viaModule = File(dir, "src/android/app/$MAIN_ROOT")
            if (viaModule.isDirectory) return viaModule
            val viaAndroid = File(dir, "app/$MAIN_ROOT")
            if (viaAndroid.isDirectory) return viaAndroid
            dir = dir.parentFile
        }
        return null
    }

    fun read(relPath: String): String {
        val root = mainRoot() ?: error("production source root not found from ${File("").absolutePath}")
        val f = File(root, relPath)
        require(f.isFile) { "production source missing: ${f.absolutePath}" }
        return f.readText()
    }

    fun allKotlinFiles(): List<File> {
        val root = mainRoot() ?: error("production source root not found")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
