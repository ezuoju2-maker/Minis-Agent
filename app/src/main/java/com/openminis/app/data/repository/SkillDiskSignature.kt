package com.openminis.app.data.repository

import java.io.File

internal object SkillDiskSignature {

    private const val BREAKER_MIN_COUNT = 3

    private const val BREAKER_RATIO = 0.40

    fun skillIdsOnDisk(skillsDir: File): Set<String> {
        val dirs = skillsDir.listFiles() ?: return emptySet()
        val ids = HashSet<String>(dirs.size)
        for (dir in dirs) {
            if (dir.isDirectory && File(dir, "SKILL.md").isFile) ids.add(dir.name)
        }
        return ids
    }

    fun compute(skillsDir: File): Long {
        val dirs = skillsDir.listFiles() ?: return 0L
        dirs.sortBy { it.name }
        var h = 1125899906842597L
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val md = File(dir, "SKILL.md")
            h = 31 * h + dir.name.hashCode()
            h = 31 * h + md.lastModified()
            h = 31 * h + md.length()
        }
        return h
    }

    fun breakerTripped(totalCustom: Int, missingCustom: Int): Boolean =
        totalCustom >= BREAKER_MIN_COUNT &&
            missingCustom >= BREAKER_MIN_COUNT &&
            missingCustom.toDouble() / totalCustom >= BREAKER_RATIO
}
