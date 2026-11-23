package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class FileMentionIndex(
    private val filesDir: File,
    private val mountsProvider: () -> List<MountEntry> = { emptyList() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(File(context.filesDir, "minis-global"))

    enum class Scope(val displayLabel: String, val order: Int, val rankBoost: Int) {
        SKILLS("skills", 0, 600),
        ATTACHMENTS("attachments", 1, 500),
        MOUNT("mount", 2, 400),
        SHARED("shared", 3, 300),
        WORKSPACE("workspace", 4, 200),
        MEMORY("memory", 5, 100),
    }

    data class MountEntry(val name: String, val root: File)

    data class Entry(
        val linuxPath: String,
        val scope: Scope,
        val mountName: String?,
        val modifiedAt: Long,
        val isDirectory: Boolean,
    ) {
        val basename: String get() = linuxPath.substringAfterLast('/').ifEmpty { linuxPath }
        val displayPath: String get() = linuxPath.removePrefix("/var/minis/")
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var lastScanSessionId: String? = null
    private var lastScanAt: Long = 0
    private var currentScanToken: UUID = UUID.randomUUID()
    private var currentJob: Job? = null

    fun refreshIfNeeded(sessionId: String) {
        val now = System.currentTimeMillis()
        val sameSession = lastScanSessionId == sessionId
        val fresh = now - lastScanAt < cacheTtlMs
        if (sameSession && fresh && _entries.value.isNotEmpty()) return

        lastScanSessionId = sessionId
        lastScanAt = now
        val token = UUID.randomUUID()
        currentScanToken = token
        currentJob?.cancel()
        currentJob = scope.launch { scan(sessionId, token) }
    }

    fun refresh(sessionId: String) {
        lastScanAt = 0
        refreshIfNeeded(sessionId)
    }

    private suspend fun scan(sessionId: String, token: UUID) {
        _isScanning.value = true
        val collected = mutableListOf<Entry>()
        try {
            layerEntries(
                sessionId = sessionId,
                layers = listOf(
                    File(filesDir, "workspace/$sessionId") to Scope.WORKSPACE,
                    File(filesDir, "attachments/$sessionId") to Scope.ATTACHMENTS,
                ),
                linuxRootFor = { scope -> "/var/minis/${scope.displayLabel}/$sessionId" },
            ).let { newBatch ->
                collected += newBatch
                publish(token, collected)
            }

            layerEntries(
                sessionId = sessionId,
                layers = listOf(
                    File(filesDir, "shared") to Scope.SHARED,
                    File(filesDir, "skills") to Scope.SKILLS,
                    File(filesDir, "memory") to Scope.MEMORY,
                ),
                linuxRootFor = { scope -> "/var/minis/${scope.displayLabel}" },
            ).let { newBatch ->
                collected += newBatch
                publish(token, collected)
            }

            var budget = GLOBAL_SCAN_BUDGET - collected.size
            for (mount in mountsProvider()) {
                if (currentScanToken != token) return
                val mountLinuxRoot = "/var/minis/mounts/${mount.name}"
                collected += Entry(
                    linuxPath = mountLinuxRoot,
                    scope = Scope.MOUNT,
                    mountName = mount.name,
                    modifiedAt = mount.root.lastModified(),
                    isDirectory = true,
                )
                if (budget > 0) {
                    val mountBatch = scanDir(
                        root = mount.root,
                        linuxRoot = mountLinuxRoot,
                        scope = Scope.MOUNT,
                        mountName = mount.name,
                        maxDepth = MOUNT_SCAN_MAX_DEPTH,
                        budget = budget,
                    )
                    collected += mountBatch
                    budget -= mountBatch.size
                }
                publish(token, collected)
            }
        } finally {
            _isScanning.value = false
        }
    }

    private fun layerEntries(
        sessionId: String,
        layers: List<Pair<File, Scope>>,
        linuxRootFor: (Scope) -> String,
    ): List<Entry> {
        val out = mutableListOf<Entry>()
        for ((dir, scope) in layers) {
            if (!dir.isDirectory) continue
            val linuxRoot = linuxRootFor(scope)
            out += Entry(
                linuxPath = linuxRoot,
                scope = scope,
                mountName = null,
                modifiedAt = dir.lastModified(),
                isDirectory = true,
            )
            out += scanDir(
                root = dir,
                linuxRoot = linuxRoot,
                scope = scope,
                mountName = null,
                maxDepth = Int.MAX_VALUE,
                budget = GLOBAL_SCAN_BUDGET,
            )
        }
        return out
    }

    private fun scanDir(
        root: File,
        linuxRoot: String,
        scope: Scope,
        mountName: String?,
        maxDepth: Int,
        budget: Int,
    ): List<Entry> {
        val out = ArrayList<Entry>()
        val rootPath = root.absolutePath
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && out.size < budget) {
            val (dir, depth) = queue.removeFirst()
            if (depth > maxDepth) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (out.size >= budget) break
                if (child.name.startsWith(".")) continue
                if (child.isDirectory && child.name in SKIP_DIR_NAMES) continue
                val relative = child.absolutePath.removePrefix(rootPath).removePrefix("/")
                if (relative.isEmpty()) continue
                val linuxPath = "$linuxRoot/$relative"
                out += Entry(
                    linuxPath = linuxPath,
                    scope = scope,
                    mountName = mountName,
                    modifiedAt = child.lastModified(),
                    isDirectory = child.isDirectory,
                )
                if (child.isDirectory && depth + 1 <= maxDepth) {
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return out
    }

    private suspend fun publish(token: UUID, collected: List<Entry>) {
        if (currentScanToken != token) return
        val deduped = collected.associateBy { it.linuxPath }.values
        val sorted = deduped.sortedWith(defaultOrdering)
        withContext(Dispatchers.Main) {
            if (currentScanToken != token) return@withContext
            _entries.value = sorted
        }
    }

    fun matches(query: String, limit: Int = DEFAULT_MATCH_LIMIT): List<Entry> {
        val all = _entries.value
        if (query.isBlank()) return all.take(limit)
        val q = query.lowercase()

        val now = System.currentTimeMillis()
        val recencyDayCapMs: Long = 56L * 24 * 60 * 60 * 1000

        val scored = mutableListOf<Pair<Int, Entry>>()
        for (entry in all) {
            val base = entry.basename.lowercase()
            val path = entry.linuxPath.lowercase()
            val stem = base.substringBeforeLast('.', missingDelimiterValue = base)

            val nameScore = when {
                base == q -> 10_000
                stem.isNotEmpty() && stem == q -> 9_000
                base.startsWith(q) -> 7_000
                basenameWordBoundaryContains(base, q) -> 6_000
                q in base -> 4_000
                anyPathComponentMatches(path, q) -> 2_000
                q in path -> 1_000
                else -> 0
            }
            if (nameScore == 0) continue

            val scopeScore = entry.scope.rankBoost

            val topLevelBonus = if (isTopLevelScopeRoot(entry)) 50 else 0

            val ageMs = (now - entry.modifiedAt).coerceAtLeast(0L)
            val recencyBonus = (80.0 *
                (1.0 - (ageMs.toDouble() / recencyDayCapMs.toDouble()).coerceIn(0.0, 1.0)))
                .toInt()
                .coerceAtLeast(0)

            scored += (nameScore + scopeScore + topLevelBonus + recencyBonus) to entry
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Int, Entry>> { it.first }
                    .thenBy { it.second.scope.order }
                    .thenByDescending { it.second.modifiedAt }
                    .thenBy { it.second.linuxPath.length }
            )
            .take(limit)
            .map { it.second }
    }

    private fun basenameWordBoundaryContains(base: String, query: String): Boolean {
        if (query.isEmpty() || base.isEmpty()) return false
        val separators = setOf('_', '-', ' ', '.', '/')
        var i = 0
        while (i < base.length) {
            if (base.startsWith(query, startIndex = i)) return true
            val sep = (i until base.length).firstOrNull { separators.contains(base[it]) }
                ?: return false
            i = sep + 1
        }
        return false
    }

    private fun anyPathComponentMatches(path: String, query: String): Boolean {
        return path.split('/').any { it.contains(query) }
    }

    private fun isTopLevelScopeRoot(entry: Entry): Boolean {
        if (!entry.isDirectory) return false
        val tops = listOf(
            "/var/minis/skills/",
            "/var/minis/mounts/",
            "/var/minis/shared/",
        )
        for (top in tops) {
            if (entry.linuxPath.startsWith(top)) {
                val rest = entry.linuxPath.removePrefix(top)
                return rest.isNotEmpty() && '/' !in rest
            }
        }
        return false
    }

    private val defaultOrdering: Comparator<Entry> = compareBy<Entry> { it.scope.order }
        .thenByDescending { isTopLevelScopeRoot(it) }
        .thenBy { it.isDirectory }
        .thenByDescending { it.modifiedAt }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 10L * 60 * 1000
        const val DEFAULT_MATCH_LIMIT = 100
        const val GLOBAL_SCAN_BUDGET = 5000
        const val MOUNT_SCAN_MAX_DEPTH = 3

        private val SKIP_DIR_NAMES = setOf(
            ".git", ".svn", ".hg",
            "node_modules", ".venv", "venv", "__pycache__",
            ".build", ".gradle", ".idea", ".tox",
            ".mypy_cache", ".pytest_cache", ".ruff_cache",
        )
    }
}
