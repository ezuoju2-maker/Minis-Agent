package com.openminis.app.tools

/**
 * [T-batch-read-after-write] Which calls of one tool batch must wait for which
 * (iOS BatchToolOrder, 1eb3e2bef; Windows `writers_before`). A reader
 * (`read_image`, `file_read`) waits for every writer (`shell_execute`,
 * `file_write`, `file_edit`) that precedes it in the batch, because models
 * routinely write a file and read it in the same turn. Nothing else is
 * ordered: writers, browser calls, sub agents and readers with no writer
 * before them still start together.
 */
object BatchToolOrder {
    val WRITERS: Set<String> = setOf("shell_execute", FileWriteTool.NAME, FileEditTool.NAME)
    val READERS: Set<String> = setOf(ReadImageTool.NAME, FileReadTool.NAME)

    /** Indices of the writers the call at [index] must wait for (empty unless it is a reader). */
    fun writersBefore(index: Int, names: List<String>): List<Int> {
        if (index !in names.indices || names[index] !in READERS) return emptyList()
        return (0 until index).filter { names[it] in WRITERS }
    }

    /**
     * [advanced-parallel] Group the batch into dependency waves: calls in the
     * same wave have no read-after-write relationship and can therefore run
     * concurrently; a reader is placed one wave after the LAST writer it must
     * wait for. This is the generalisation of [writersBefore] that lets a
     * scheduler issue whole waves at once instead of joining writer jobs one
     * by one — fewer barriers, same correctness.
     *
     * Returns waves as lists of indices, in execution order. Deterministic.
     */
    fun waves(names: List<String>): List<List<Int>> {
        if (names.isEmpty()) return emptyList()
        // wave(i) = max(wave(dep) + 1) over dependencies. Because every writer
        // has no deps (wave 0), a reader's wave is 1 + the max wave of its
        // writers; a writer after a reader is still wave 0.
        val waveOf = IntArray(names.size)
        for (i in names.indices) {
            val deps = writersBefore(i, names)
            waveOf[i] = if (deps.isEmpty()) 0 else deps.maxOf { waveOf[it] } + 1
        }
        val maxWave = waveOf.maxOrNull() ?: 0
        return (0..maxWave).map { w -> waveOf.indices.filter { waveOf[it] == w } }
            .filter { it.isNotEmpty() }
    }

    /**
     * True iff [names] contain at least one reader that must wait for an
     * earlier writer — i.e. a plain "run all in parallel" would be incorrect.
     */
    fun needsOrdering(names: List<String>): Boolean =
        names.indices.any { writersBefore(it, names).isNotEmpty() }
}
