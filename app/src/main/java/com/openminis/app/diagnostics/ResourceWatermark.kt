package com.openminis.app.diagnostics

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

object ResourceWatermark {

    const val THREAD_ALERT = 400

    const val MAPS_ALERT = 30_000

    const val ALERT_MIN_INTERVAL_MS = 5 * 60_000L

    const val ALERT_GROWTH = 1.25

    const val ALERT_REARM = 0.8

    const val MAPS_COUNT_CAP = 70_000

    const val TASK_SCAN_CAP = 8_192

    const val TOP_N = 15

    data class Sample(
        val threads: Int,
        val maps: Int,
        val fds: Int,
        val vmSizeKb: Long,
        val vmRssKb: Long,
    ) {
        fun fields(): String =
            "threads=$threads maps=${if (maps >= MAPS_COUNT_CAP) ">=$MAPS_COUNT_CAP" else "$maps"} fds=$fds " +
                "vmSizeMB=${mb(vmSizeKb)} rssMB=${mb(vmRssKb)}"

        private fun mb(kb: Long) = if (kb < 0) "-1" else "${kb / 1024}"
    }


    fun sample(proc: File = File("/proc/self")): Sample {
        val status = readStatus(File(proc, "status"))
        return Sample(
            threads = status[0].toInt(),
            maps = countMaps(File(proc, "maps")),
            fds = countFds(File(proc, "fd")),
            vmSizeKb = status[1],
            vmRssKb = status[2],
        )
    }

    internal fun readStatus(file: File): LongArray {
        val out = longArrayOf(-1, -1, -1)
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val slot = when {
                        line.startsWith("Threads:") -> 0
                        line.startsWith("VmSize:") -> 1
                        line.startsWith("VmRSS:") -> 2
                        else -> continue
                    }
                    out[slot] = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1
                }
            }
        }
        return out
    }

    internal fun countMaps(file: File): Int =
        runCatching { FileInputStream(file).use { countLines(it, MAPS_COUNT_CAP) } }.getOrDefault(-1)

    internal fun countLines(input: InputStream, cap: Int): Int {
        val buf = ByteArray(16 * 1024)
        var lines = 0
        while (lines < cap) {
            val n = input.read(buf)
            if (n <= 0) break
            for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) lines++
        }
        return minOf(lines, cap)
    }

    internal fun countFds(dir: File): Int = runCatching { dir.list()?.size ?: -1 }.getOrDefault(-1)


    fun threadNames(taskDir: File = File("/proc/self/task")): List<String> {
        val tids = runCatching { taskDir.list() }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(minOf(tids.size, TASK_SCAN_CAP))
        for (tid in tids) {
            if (out.size >= TASK_SCAN_CAP) break
            val name = runCatching { File(taskDir, "$tid/comm").readText().trim() }.getOrNull() ?: continue
            out.add(name)
        }
        return out
    }

    fun nameKey(name: String): String = name.trim().replace(DIGITS, "#").ifEmpty { "(empty)" }

    private val DIGITS = Regex("[0-9]+")

    fun breakdown(names: List<String>, topN: Int = TOP_N): String {
        if (names.isEmpty()) return "(unavailable)"
        val counts = HashMap<String, Int>()
        for (n in names) { val k = nameKey(n); counts[k] = (counts[k] ?: 0) + 1 }
        val sorted = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val shown = sorted.take(topN).joinToString(",") { "${it.key}:${it.value}" }
        val rest = sorted.drop(topN).sumOf { it.value }
        return if (rest > 0) "$shown,other:$rest" else shown
    }

    fun mapsBreakdown(file: File = File("/proc/self/maps"), topN: Int = TOP_N): String =
        runCatching {
            val names = ArrayList<String>()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (names.size >= MAPS_COUNT_CAP) break
                    names.add(mapName(line))
                }
            }
            breakdown(names, topN)
        }.getOrDefault("(unavailable)")

    internal fun mapName(line: String): String {
        var field = 0
        var i = 0
        while (i < line.length && field < 5) {
            while (i < line.length && line[i] != ' ') i++
            while (i < line.length && line[i] == ' ') i++
            field++
        }
        val name = if (field < 5 || i >= line.length) "[anon]" else line.substring(i).trim()
        val dataDir = name.indexOf("/com.openminis.app/")
        val short = if (dataDir >= 0 && name.startsWith("/data/")) {
            val rest = name.substring(dataDir + "/com.openminis.app/".length)
            "<app>/" + rest.substringBefore('/')
        } else name
        return nameKey(short)
    }


    class AlertGate(
        private val threadAlert: Int = THREAD_ALERT,
        private val mapsAlert: Int = MAPS_ALERT,
        private val minIntervalMs: Long = ALERT_MIN_INTERVAL_MS,
    ) {
        private var armed = true
        private var lastAtMs = 0L
        private var lastThreads = 0
        private var lastMaps = 0

        fun shouldReport(s: Sample, nowMs: Long): Boolean {
            val over = s.threads > threadAlert || s.maps > mapsAlert
            if (!over) {
                if (s.threads < threadAlert * ALERT_REARM && s.maps < mapsAlert * ALERT_REARM) armed = true
                return false
            }
            val report = when {
                armed -> true
                nowMs - lastAtMs < minIntervalMs -> false
                else -> s.threads >= lastThreads * ALERT_GROWTH || s.maps >= lastMaps * ALERT_GROWTH
            }
            if (report) { armed = false; lastAtMs = nowMs; lastThreads = s.threads; lastMaps = s.maps }
            return report
        }
    }


    const val WATERMARK_DIR = "diag"
    private const val WATERMARK_PREFIX = "res-"
    private const val WATERMARK_KEEP = 8

    fun watermarkFile(filesDir: File, pid: Int): File = File(File(filesDir, WATERMARK_DIR), "$WATERMARK_PREFIX$pid.txt")

    fun writeWatermark(filesDir: File, pid: Int, text: String) {
        runCatching {
            val f = watermarkFile(filesDir, pid)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun readWatermark(filesDir: File, pid: Int): Pair<String, Long>? = runCatching {
        val f = watermarkFile(filesDir, pid)
        if (!f.isFile) null else f.readText() to f.lastModified()
    }.getOrNull()

    fun pruneWatermarks(filesDir: File, ownPid: Int, keep: Int = WATERMARK_KEEP) {
        runCatching {
            val own = watermarkFile(filesDir, ownPid).name
            File(filesDir, WATERMARK_DIR).listFiles { f -> f.name.startsWith(WATERMARK_PREFIX) && f.name != own }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }
}

object ThreadNames {
    const val PREFIX = "mn-"
    fun of(component: String): String = PREFIX + component
}
