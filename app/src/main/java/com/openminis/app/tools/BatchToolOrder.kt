package com.openminis.app.tools

object BatchToolOrder {
    val WRITERS: Set<String> = setOf("shell_execute", FileWriteTool.NAME, FileEditTool.NAME)
    val READERS: Set<String> = setOf(ReadImageTool.NAME, FileReadTool.NAME)

    fun writersBefore(index: Int, names: List<String>): List<Int> {
        if (index !in names.indices || names[index] !in READERS) return emptyList()
        return (0 until index).filter { names[it] in WRITERS }
    }

    fun waves(names: List<String>): List<List<Int>> {
        if (names.isEmpty()) return emptyList()
        val waveOf = IntArray(names.size)
        for (i in names.indices) {
            val deps = writersBefore(i, names)
            waveOf[i] = if (deps.isEmpty()) 0 else deps.maxOf { waveOf[it] } + 1
        }
        val maxWave = waveOf.maxOrNull() ?: 0
        return (0..maxWave).map { w -> waveOf.indices.filter { waveOf[it] == w } }
            .filter { it.isNotEmpty() }
    }

    fun needsOrdering(names: List<String>): Boolean =
        names.indices.any { writersBefore(it, names).isNotEmpty() }
}
