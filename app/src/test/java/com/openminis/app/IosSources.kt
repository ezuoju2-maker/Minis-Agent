package com.openminis.app

import java.io.File

object IosSources {

    val root: File? by lazy {
        var dir: File? = ProductionSources.mainRoot() ?: File("").absoluteFile
        while (dir != null && !File(dir, "src/ios").isDirectory) dir = dir.parentFile
        dir?.let { File(it, "src/ios") }
    }

    val swiftFiles: List<File> by lazy {
        val r = root ?: error("src/ios not found from ${File("").absolutePath}")
        r.walkTopDown()
            .onEnter { it.name != "MinisTests" && it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "swift" }
            .toList()
    }

    private val texts = HashMap<File, String>()

    fun text(f: File): String = synchronized(texts) { texts.getOrPut(f) { f.readText() } }

    fun declaring(declaration: String): String {
        val hits = swiftFiles.filter { text(it).contains(declaration) }
        require(hits.isNotEmpty()) { "declaration not found in src/ios: $declaration" }
        require(hits.size == 1) { "declaration is ambiguous in src/ios: $declaration in ${hits.map { it.name }}" }
        return text(hits.single())
    }

    fun anyMatches(pattern: Regex): Boolean = swiftFiles.any { pattern.containsMatchIn(text(it)) }
}
