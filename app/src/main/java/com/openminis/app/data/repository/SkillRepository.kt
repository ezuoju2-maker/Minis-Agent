package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SkillRepository(private val context: Context) {

    companion object {
        private const val TAG = "SkillRepository"
        private const val DB_NAME = "skills.db"
        private const val DB_VERSION = 3
        private const val MAX_SKILLS_IN_PROMPT = 20
        private const val MAX_SKILL_DESC_LENGTH = 200
        private const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000
        private const val RECENT_SLOTS = 10
        private const val NORMALIZE_THRESHOLD = 1000.0

        private const val EXPORT_TTL_MS = 24L * 3600 * 1000

        internal fun parseSkillMd(content: String): ParsedSkill? {
            val trimmed = content.removePrefix("﻿").trimStart()
            if (!trimmed.startsWith("---")) return null

            val lines = trimmed.lines()
            var frontmatterEndLine = -1
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") {
                    frontmatterEndLine = i
                    break
                }
            }
            if (frontmatterEndLine < 0) return null

            var name = ""
            var description = ""
            var version = "1.0.0"

            var i = 1
            while (i < frontmatterEndLine) {
                val line = lines[i]
                val colonIdx = line.indexOf(':')
                if (colonIdx < 0) { i++; continue }
                val key = line.substring(0, colonIdx).trim().lowercase()
                val rawValue = line.substring(colonIdx + 1).trim()

                val isBlockScalar = rawValue.startsWith("|") || rawValue.startsWith(">")
                val resolved: String
                if (isBlockScalar && i + 1 < frontmatterEndLine) {
                    val fold = rawValue.startsWith(">")
                    val blockLines = mutableListOf<String>()
                    var j = i + 1
                    while (j < frontmatterEndLine) {
                        val next = lines[j]
                        if (next.isEmpty() || next[0].isWhitespace()) {
                            blockLines.add(next.trim())
                        } else break
                        j++
                    }
                    resolved = if (fold) {
                        blockLines.joinToString(" ").trim()
                    } else {
                        blockLines.joinToString("\n").trim('\n')
                    }
                    i = j
                } else {
                    resolved = rawValue
                    i++
                }

                when (key) {
                    "name" -> name = resolved
                    "description" -> description = resolved
                    "version" -> version = resolved
                }
            }

            if (name.isBlank()) return null

            val bodyStartLine = frontmatterEndLine + 1
            val body = if (bodyStartLine < lines.size) {
                lines.subList(bodyStartLine, lines.size).joinToString("\n").trim('\n')
            } else ""

            return ParsedSkill(name, description, version, body)
        }
    }

    private val baseHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val httpClient: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseHttpClient)

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    enum class ImportSource(val value: String) {
        URL("url"),
        FILE("file"),
        BUNDLED("bundled"),
        SESSION("session");

        companion object {
            fun from(value: String): ImportSource =
                entries.find { it.value == value } ?: FILE
        }
    }

    data class Skill(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val version: String = "1.0.0",
        val importSource: ImportSource = ImportSource.FILE,
        val isEnabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val body: String = "",
        val sourceURL: String? = null,
        val useCount: Double = 0.0,
    )

    enum class UsageFrequency { NEVER, LOW, REGULAR, HIGH }


    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val db: SQLiteDatabase by lazy {
        SkillDbHelper(context).writableDatabase
    }

    private val skillsDir: File
        get() = File(context.filesDir, "minis-global/skills")

    private val skillsLock = Any()
    private var mutationSeq = 0L
    private val reloadRequests = Channel<String>(Channel.CONFLATED)
    private val forceReloadPending = AtomicBoolean(false)
    @Volatile private var lastDiskSignature: Long? = null

    init {
        runCatching { loadAll() }.onFailure {
            Log.e(TAG, "loadAll failed — continuing with ${_skills.value.size} skill(s): ${it.message}", it)
        }
        runCatching { installBundledSkills() }.onFailure {
            Log.e(TAG, "installBundledSkills failed — continuing: ${it.message}", it)
        }
        startReloadWorker()
    }


    fun add(name: String, description: String, body: String, version: String = "1.0.0", source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val id = slugify(name)
        if (id.isBlank()) return null
        if (_skills.value.any { it.id == id }) return null

        val skill = Skill(
            id = id,
            name = name,
            description = description,
            version = version,
            importSource = source,
            body = body,
            sourceURL = sourceURL,
        )

        insertDb(skill)
        writeSkillMd(skill)
        mutateSkills { it + skill }
        Log.i(TAG, "Added skill: ${skill.id}")
        return skill
    }

    fun update(id: String, name: String? = null, description: String? = null, body: String? = null): Boolean {
        val current = _skills.value.find { it.id == id } ?: return false
        val updated = current.copy(
            name = name ?: current.name,
            description = description ?: current.description,
            body = body ?: current.body,
            updatedAt = System.currentTimeMillis(),
        )

        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(updated.name, updated.description, updated.version, updated.updatedAt, id)
        )
        writeSkillMd(updated)
        mutateSkills { list -> list.map { if (it.id == id) updated else it } }
        return true
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
        db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
        val dir = File(skillsDir, id)
        dir.deleteRecursively()
        mutateSkills { list -> list.filter { it.id != id } }
        Log.i(TAG, "Deleted skill: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        db.execSQL("UPDATE skills SET is_enabled=? WHERE id=?", arrayOf<Any>(if (enabled) 1 else 0, id))
        mutateSkills { list ->
            list.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
        }
    }


    fun isEnabledForSession(skillId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
            arrayOf(sessionId, skillId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _skills.value.find { it.id == skillId }?.isEnabled ?: false
    }

    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        db.execSQL(
            "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
            arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0)
        )
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE session_skill_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
    }


    fun skillPromptFragment(sessionId: String): String? {
        val enabled = _skills.value.filter { isEnabledForSession(it.id, sessionId) }
        if (enabled.isEmpty()) return null

        val total = enabled.size
        val selected: List<Skill>
        val hasMore: Boolean

        if (total <= MAX_SKILLS_IN_PROMPT) {
            selected = enabled.sortedByDescending { it.updatedAt }
            hasMore = false
        } else {
            val picked = linkedMapOf<String, Skill>()
            enabled.filter { it.importSource == ImportSource.BUNDLED }
                .forEach { picked.putIfAbsent(it.id, it) }
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            val recentLimit = minOf(RECENT_SLOTS, MAX_SKILLS_IN_PROMPT - picked.size).coerceAtLeast(0)
            enabled.asSequence()
                .filter { it.updatedAt > cutoff && it.id !in picked }
                .sortedByDescending { it.updatedAt }
                .take(recentLimit)
                .forEach { picked.putIfAbsent(it.id, it) }
            if (picked.size < MAX_SKILLS_IN_PROMPT) {
                val remaining = MAX_SKILLS_IN_PROMPT - picked.size
                enabled.asSequence()
                    .filter { it.id !in picked }
                    .sortedByDescending { it.useCount }
                    .take(remaining)
                    .forEach { picked.putIfAbsent(it.id, it) }
            }
            selected = picked.values.toList()
            hasMore = total > selected.size
        }

        val xml = buildString {
            append("<available_skills>\n")
            for (skill in selected) {
                var desc = skill.description
                if (desc.length > MAX_SKILL_DESC_LENGTH) {
                    desc = desc.substring(0, MAX_SKILL_DESC_LENGTH) + "…"
                }
                append("  <skill>\n")
                append("    <name>").append(escapeXml(skill.name)).append("</name>\n")
                append("    <description>").append(escapeXml(desc)).append("</description>\n")
                append("    <path>/var/minis/skills/").append(skill.id).append("/SKILL.md</path>\n")
                append("  </skill>\n")
            }
            append("</available_skills>")
        }

        return buildString {
            append("Skills:\n")
            append("Reusable instruction sets stored at /var/minis/skills/<name>/SKILL.md. Read the SKILL.md file to load full instructions before using a skill.\n\n")
            append(xml)
            if (hasMore) {
                val selectedIds = selected.mapTo(HashSet(selected.size)) { it.id }
                val omitted = enabled.filter { it.id !in selectedIds }
                val maxUndisclosed = (100 - selected.size).coerceAtLeast(0)
                val names = omitted.take(maxUndisclosed).joinToString(", ") { it.name }
                append("\n\n")
                append(omitted.size).append(" more skills not shown above: ").append(names)
                append(". List or search /var/minis/skills/ to find the rest.")
            }
        }
    }

    fun recordSkillUse(skillId: String) {
        val current = _skills.value.find { it.id == skillId } ?: return
        val bumped = current.copy(useCount = current.useCount + 1.0)
        db.execSQL(
            "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
            arrayOf<Any>(skillId)
        )
        mutateSkills { list ->
            val next = list.map { if (it.id == skillId) bumped else it }
            if (bumped.useCount > NORMALIZE_THRESHOLD) normalizeUseCounts(next) else next
        }
    }

    private fun normalizeUseCounts(list: List<Skill>): List<Skill> {
        val maxCount = list.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return list
        val scaled = list.map { it.copy(useCount = it.useCount / maxCount * 100.0) }
        db.beginTransaction()
        try {
            for (s in scaled) {
                db.execSQL(
                    "UPDATE skills SET use_count=? WHERE id=?",
                    arrayOf<Any>(s.useCount, s.id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return scaled
    }

    fun usageFrequency(skillId: String): UsageFrequency {
        val skill = _skills.value.find { it.id == skillId } ?: return UsageFrequency.NEVER
        if (skill.useCount == 0.0) return UsageFrequency.NEVER
        val maxCount = _skills.value.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return UsageFrequency.NEVER
        val normalized = skill.useCount / maxCount * 100.0
        return when {
            normalized < 20.0 -> UsageFrequency.LOW
            normalized < 60.0 -> UsageFrequency.REGULAR
            else -> UsageFrequency.HIGH
        }
    }

    fun skillIdFromPath(path: String): String? {
        val prefix = "/var/minis/skills/"
        if (!path.startsWith(prefix)) return null
        if (!path.endsWith("/SKILL.md")) return null
        val rest = path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val candidate = rest.substring(0, slash)
        return if (_skills.value.any { it.id == candidate }) candidate else null
    }


    fun importFromContent(content: String, source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val parsed = parseSkillMd(content) ?: return null
        val id = slugify(parsed.name)
        if (id.isNotBlank() && _skills.value.any { it.id == id }) {
            val current = _skills.value.first { it.id == id }
            val replaced = current.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                importSource = source,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
                sourceURL = sourceURL ?: current.sourceURL,
            )
            db.execSQL(
                "UPDATE skills SET name=?, description=?, version=?, import_source=?, source_url=?, updated_at=? WHERE id=?",
                arrayOf<Any?>(
                    replaced.name, replaced.description, replaced.version,
                    replaced.importSource.value, replaced.sourceURL,
                    replaced.updatedAt, id
                )
            )
            writeSkillMd(replaced)
            mutateSkills { list -> list.map { if (it.id == id) replaced else it } }
            return replaced
        }
        return add(
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            version = parsed.version,
            source = source,
            sourceURL = sourceURL,
        )
    }

    fun skillMdPath(id: String): String = "/var/minis/skills/$id/SKILL.md"


    fun importFromArchive(input: InputStream): Skill? {
        val entries = try { readZipEntries(input) } catch (e: Exception) {
            Log.w(TAG, "Failed to read zip archive: ${e.message}")
            return null
        }
        if (entries.isEmpty()) return null

        val skillMdEntry = entries.firstOrNull { entry ->
            val name = entry.name
            name == "SKILL.md" || (name.endsWith("/SKILL.md") && name.count { it == '/' } == 1)
        } ?: return null

        val skillContent = try { String(skillMdEntry.data, Charsets.UTF_8) } catch (_: Exception) { return null }
        if (skillContent.isBlank()) return null

        val skill = importFromContent(skillContent, ImportSource.FILE) ?: return null

        val prefix = if (skillMdEntry.name == "SKILL.md") "" else skillMdEntry.name.dropLast("SKILL.md".length)
        val skillDir = File(skillsDir, skill.id)
        skillDir.mkdirs()

        for (entry in entries) {
            if (entry.isDirectory) continue
            var relativePath = entry.name
            if (prefix.isNotEmpty() && relativePath.startsWith(prefix)) {
                relativePath = relativePath.drop(prefix.length)
            }
            if (relativePath == "SKILL.md" || relativePath.startsWith(".") || relativePath.isEmpty()) continue
            if (relativePath.contains("..")) continue

            val destFile = File(skillDir, relativePath)
            destFile.parentFile?.mkdirs()
            try { destFile.writeBytes(entry.data) } catch (e: Exception) {
                Log.w(TAG, "Failed to write $relativePath: ${e.message}")
            }
        }
        return skill
    }

    private data class ZipEntryData(val name: String, val isDirectory: Boolean, val data: ByteArray)

    private fun readZipEntries(input: InputStream): List<ZipEntryData> {
        val out = mutableListOf<ZipEntryData>()
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                val isDir = entry.isDirectory
                val data = if (isDir) ByteArray(0) else {
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zis.read(chunk)
                        if (n <= 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                out.add(ZipEntryData(name, isDir, data))
                zis.closeEntry()
            }
        }
        return out
    }


    data class GitHubInfo(
        val user: String,
        val repo: String,
        val branch: String,
        val dirPath: String,
    )

    suspend fun importFromGitHub(urlString: String): Skill? = withContext(Dispatchers.IO) {
        val rawURL = githubToRawURL(urlString) ?: return@withContext null
        val content = httpGetString(rawURL) ?: return@withContext null
        val skill = importFromContent(content, ImportSource.URL, sourceURL = urlString) ?: return@withContext null

        val ghInfo = parseGitHubURL(urlString) ?: return@withContext skill
        val skillDir = File(skillsDir, skill.id)
        backgroundScope.launch {
            val outcome = downloadSiblingFiles(ghInfo, skillDir, skill)
            val bumped = skill.copy(updatedAt = System.currentTimeMillis())
            db.execSQL(
                "UPDATE skills SET updated_at=? WHERE id=?",
                arrayOf<Any>(bumped.updatedAt, skill.id)
            )
            mutateSkills { list -> list.map { if (it.id == skill.id) bumped else it } }
            if (!outcome.isComplete) {
                Log.w(TAG, "importFromGitHub partial for ${skill.id}: ${outcome.reason ?: "${outcome.filesFailed} file(s) failed"}")
            }
        }
        skill
    }

    sealed class UpdateResult {
        data class Success(val skill: Skill) : UpdateResult()
        data class PartialSuccess(val skill: Skill, val reason: String) : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    data class SiblingDownloadOutcome(
        val filesWritten: Int,
        val filesFailed: Int,
        val reason: String?,
    ) {
        val isComplete: Boolean get() = filesFailed == 0 && reason == null
    }

    suspend fun updateFromURL(skillId: String): UpdateResult = withContext(Dispatchers.IO) {
        val existing = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill not found")
        if (existing.importSource != ImportSource.URL) {
            return@withContext UpdateResult.Failure("Skill was not imported from a URL")
        }
        val urlString = existing.sourceURL
        if (urlString.isNullOrBlank()) {
            return@withContext UpdateResult.Failure(
                "No source URL on file. Re-import this skill from Minis Skills to enable updates."
            )
        }

        val rawURL = githubToRawURL(urlString)
            ?: return@withContext UpdateResult.Failure("Could not build a raw download URL from $urlString")
        val content = try { httpGetString(rawURL) } catch (e: Exception) {
            return@withContext UpdateResult.Failure("Network error: ${e.message ?: "unknown"}")
        }
        if (content == null) {
            return@withContext UpdateResult.Failure("Download failed (HTTP error) for $rawURL")
        }
        val parsed = parseSkillMd(content)
            ?: return@withContext UpdateResult.Failure("Downloaded SKILL.md is not valid (missing YAML frontmatter or 'name:' field)")

        val ok = update(
            id = skillId,
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
        )
        if (!ok) return@withContext UpdateResult.Failure("Failed to write updated skill")

        val ghInfo = parseGitHubURL(urlString)
        var siblingOutcome: SiblingDownloadOutcome? = null
        if (ghInfo != null) {
            val skillDir = File(skillsDir, skillId)
            val current = _skills.value.first { it.id == skillId }
            siblingOutcome = downloadSiblingFiles(ghInfo, skillDir, current)
        }

        val fresh = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill disappeared during update")
        if (siblingOutcome != null && !siblingOutcome.isComplete) {
            val reason = siblingOutcome.reason
                ?: "${siblingOutcome.filesFailed} sibling file(s) failed to download"
            return@withContext UpdateResult.PartialSuccess(fresh, reason)
        }
        UpdateResult.Success(fresh)
    }


    fun exportSkillToZip(skillId: String): File? {
        val skill = _skills.value.find { it.id == skillId }
        val dir = File(skillsDir, skillId)
        if (!dir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: no directory for $skillId")
            return null
        }
        val relPaths = listSkillFiles(skillId)
        if (relPaths.isEmpty()) {
            Log.w(TAG, "exportSkillToZip: $skillId has no files to export")
            return null
        }

        sweepStaleExports()

        val exportDir = File(File(context.cacheDir, "share"), "skill-export-${UUID.randomUUID()}")
        if (!exportDir.mkdirs() && !exportDir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: could not create $exportDir")
            return null
        }
        val safeName = (skill?.name ?: skillId)
            .replace('/', '-')
            .replace(':', '-')
            .replace('\\', '-')
            .trim()
            .ifEmpty { "skill" }
        val zipFile = File(exportDir, "$safeName.zip")

        return try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                for (rel in relPaths) {
                    val src = File(dir, rel)
                    if (!src.isFile) continue
                    zos.putNextEntry(ZipEntry(rel.replace(File.separatorChar, '/')))
                    FileInputStream(src).buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            if (!zipFile.isFile || zipFile.length() <= 0L) {
                Log.w(TAG, "exportSkillToZip: produced an empty zip for $skillId")
                zipFile.delete()
                return null
            }
            Log.i(TAG, "exportSkillToZip: $skillId -> ${zipFile.name} (${zipFile.length()} bytes, ${relPaths.size} files)")
            zipFile
        } catch (e: Exception) {
            Log.w(TAG, "exportSkillToZip failed for $skillId: ${e.message}")
            runCatching { zipFile.delete() }
            null
        }
    }

    private fun sweepStaleExports() {
        val root = File(context.cacheDir, "share")
        val now = System.currentTimeMillis()
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            if (!entry.isDirectory || !entry.name.startsWith("skill-export-")) continue
            if (now - entry.lastModified() < EXPORT_TTL_MS) continue
            runCatching { entry.deleteRecursively() }
                .onFailure { Log.w(TAG, "sweepStaleExports: could not delete ${entry.name}") }
        }
    }

    fun listSkillFiles(skillId: String): List<String> {
        val dir = File(skillsDir, skillId)
        if (!dir.isDirectory) return emptyList()
        val rootLen = dir.absolutePath.length + 1
        val files = dir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.absolutePath.substring(rootLen) }
            .toList()
        return files.sortedWith(compareBy(
            { if (it.equals("SKILL.md", ignoreCase = true)) 0 else 1 },
            { it },
        ))
    }

    fun skillFileHostPath(skillId: String, relativePath: String): String =
        File(File(skillsDir, skillId), relativePath).absolutePath

    fun rescanFromDisk(skillId: String): Skill? {
        val current = _skills.value.find { it.id == skillId } ?: return null
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (!file.exists()) {
            if (current.importSource != ImportSource.BUNDLED) {
                delete(skillId)
                Log.i(TAG, "Rescan found no SKILL.md — removed orphan skill: $skillId")
            }
            return null
        }
        val parsed = parseSkillMd(file.readText()) ?: return null
        val refreshed = current.copy(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            body = parsed.body,
            updatedAt = System.currentTimeMillis(),
        )
        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.description, refreshed.version, refreshed.updatedAt, skillId)
        )
        mutateSkills { list -> list.map { if (it.id == skillId) refreshed else it } }
        return refreshed
    }

    fun renameSkill(skillId: String, newName: String): Boolean {
        val current = _skills.value.find { it.id == skillId } ?: return false
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (file.exists()) {
            val original = file.readText()
            val updated = original.replaceFirst(Regex("(?m)^name:\\s*.*$"), "name: $trimmed")
            if (updated != original) file.writeText(updated)
        }
        val refreshed = current.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        db.execSQL(
            "UPDATE skills SET name=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.updatedAt, skillId)
        )
        mutateSkills { list -> list.map { if (it.id == skillId) refreshed else it } }
        return true
    }

    fun readSkillFile(skillId: String, relativePath: String): String? {
        if (relativePath.contains("..")) return null
        val file = File(File(skillsDir, skillId), relativePath)
        if (!file.isFile) return null
        return try { file.readText() } catch (_: Exception) { null }
    }

    fun writeSkillFile(skillId: String, relativePath: String, content: String): Boolean {
        if (relativePath.contains("..")) return false
        val file = File(File(skillsDir, skillId), relativePath)
        file.parentFile?.mkdirs()
        return try {
            file.writeText(content)
            if (relativePath.equals("SKILL.md", ignoreCase = true)) rescanFromDisk(skillId)
            true
        } catch (_: Exception) { false }
    }

    private suspend fun downloadSiblingFiles(ghInfo: GitHubInfo, destDir: File, skill: Skill): SiblingDownloadOutcome {
        Log.i(TAG, "[siblings] start skill=${skill.id} dir=${ghInfo.dirPath} repo=${ghInfo.user}/${ghInfo.repo}@${ghInfo.branch}")
        val agg = AggregateOutcome()
        downloadGitHubDirectory(
            user = ghInfo.user,
            repo = ghInfo.repo,
            branch = ghInfo.branch,
            remotePath = ghInfo.dirPath,
            localDir = destDir,
            relativeTo = "",
            skill = skill,
            depth = 0,
            outcome = agg,
        )
        val finalReason = agg.firstReason
        Log.i(TAG, "[siblings] done skill=${skill.id} written=${agg.filesWritten} failed=${agg.filesFailed} reason=${finalReason ?: "(none)"}")
        return SiblingDownloadOutcome(agg.filesWritten, agg.filesFailed, finalReason)
    }

    private class AggregateOutcome {
        var filesWritten = 0
        var filesFailed = 0
        var firstReason: String? = null
        fun recordFailure(reason: String) {
            filesFailed += 1
            if (firstReason == null) firstReason = reason
        }
        fun recordReason(reason: String) {
            if (firstReason == null) firstReason = reason
        }
    }

    private suspend fun downloadGitHubDirectory(
        user: String,
        repo: String,
        branch: String,
        remotePath: String,
        localDir: File,
        relativeTo: String,
        skill: Skill,
        depth: Int,
        outcome: AggregateOutcome,
    ) {
        if (depth > 5) {
            val msg = "Max recursion depth (5) at $remotePath — stopping"
            Log.w(TAG, "[siblings] $msg")
            outcome.recordReason(msg)
            return
        }
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8").replace("+", "%20").replace("%2F", "/")
        val apiURL = "https://api.github.com/repos/$user/$repo/contents/$encodedPath?ref=$branch"

        val body = fetchContentsWithRetry(apiURL, outcome) ?: return

        val items = try {
            JSONArray(body)
        } catch (e: Exception) {
            val snippet = body.lineSequence().firstOrNull()?.take(160) ?: "(empty)"
            val msg = "GitHub contents API returned non-array JSON for $remotePath: $snippet"
            Log.w(TAG, "[siblings] $msg (${e.javaClass.simpleName}: ${e.message})")
            outcome.recordReason(msg)
            return
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val type = item.optString("type")
            val relPath = if (relativeTo.isEmpty()) name else "$relativeTo/$name"

            if (type == "dir") {
                val subRemote = if (remotePath.isEmpty()) name else "$remotePath/$name"
                downloadGitHubDirectory(
                    user = user, repo = repo, branch = branch,
                    remotePath = subRemote, localDir = localDir, relativeTo = relPath,
                    skill = skill, depth = depth + 1, outcome = outcome,
                )
                continue
            }

            if (type != "file") continue
            if (relativeTo.isEmpty() && name.equals("SKILL.md", ignoreCase = true)) continue
            val downloadURL = item.optString("download_url")
            if (downloadURL.isEmpty()) {
                Log.w(TAG, "[siblings] missing download_url for $relPath — skipping")
                outcome.recordFailure("Missing download_url for $relPath")
                continue
            }

            val fileData = fetchBytesWithRetry(downloadURL)
            if (fileData == null) {
                val msg = "Failed to download $relPath from $downloadURL"
                Log.w(TAG, "[siblings] $msg")
                outcome.recordFailure(msg)
                continue
            }
            val destFile = File(localDir, relPath)
            destFile.parentFile?.mkdirs()
            val existing = if (destFile.exists()) destFile.readBytes() else null
            if (existing == null || !existing.contentEquals(fileData)) {
                destFile.writeBytes(fileData)
                Log.i(TAG, "[siblings] wrote $relPath (${fileData.size}B)")
            } else {
                Log.i(TAG, "[siblings] kept $relPath (unchanged, ${fileData.size}B)")
            }
            outcome.filesWritten += 1
        }
    }

    private suspend fun fetchContentsWithRetry(apiURL: String, outcome: AggregateOutcome): String? {
        var lastReason: String? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(apiURL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) return body
                        lastReason = "GitHub contents API returned empty body"
                    } else {
                        val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                        val hint = if (resp.code == 403) " (likely anonymous rate limit — wait an hour or sign in)" else ""
                        lastReason = "GitHub contents API HTTP ${resp.code}$hint for $apiURL"
                        if (!isTransient) {
                            Log.w(TAG, "[siblings] non-retryable ${lastReason}")
                            outcome.recordReason(lastReason!!)
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                lastReason = "GitHub contents API ${e.javaClass.simpleName}: ${e.message ?: "unknown"} for $apiURL"
            }
            if (attempt == 0) {
                Log.w(TAG, "[siblings] retrying after transient failure: $lastReason")
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "[siblings] both attempts failed: $lastReason")
        outcome.recordReason(lastReason ?: "GitHub contents API unavailable")
        return null
    }

    private suspend fun fetchBytesWithRetry(url: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return resp.body?.bytes()
                    val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                    if (!isTransient) {
                        Log.w(TAG, "[siblings] HTTP ${resp.code} non-retryable for $url")
                        return null
                    }
                    Log.w(TAG, "[siblings] HTTP ${resp.code} on $url (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[siblings] ${e.javaClass.simpleName}: ${e.message} for $url (attempt ${attempt + 1})")
            }
            if (attempt == 0) {
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun parseGitHubURL(urlString: String): GitHubInfo? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            if (parts.size < 3) return null
            val user = parts[0]; val repo = parts[1]; val branch = parts[2]
            val dirParts = parts.drop(3).toMutableList()
            if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
                dirParts.removeAt(dirParts.size - 1)
            }
            return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
        }

        if (host != "github.com") return null
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val dirParts = parts.drop(4).toMutableList()
        if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
            dirParts.removeAt(dirParts.size - 1)
        }
        return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
    }

    private fun githubToRawURL(urlString: String): String? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            return if (parts.lastOrNull()?.equals("SKILL.md", ignoreCase = true) == true) {
                normalized
            } else {
                val base = normalized.trimEnd('/')
                "$base/SKILL.md"
            }
        }

        if (host != "github.com") return null
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val pathParts = parts.drop(4).toMutableList()
        var path = pathParts.joinToString("/")
        if (!path.endsWith("SKILL.md", ignoreCase = true)) {
            path = if (path.isEmpty()) "SKILL.md" else "$path/SKILL.md"
        }
        return "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
    }

    private fun normalizeURL(urlString: String): String {
        val trimmed = urlString.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun httpGetString(url: String): String? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetString failed $url: ${e.message}")
        null
    }

    private fun httpGetBytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetBytes failed $url: ${e.message}")
        null
    }



    private fun installBundledSkills() {
        val bundledId = "skill-creator"
        val bundledVersion = "2.0.0"
        val existing = _skills.value.find { it.id == bundledId }

        if (existing != null && existing.version >= bundledVersion) return

        val parsed = parseSkillMd(SKILL_CREATOR_CONTENT) ?: return
        if (existing != null) {
            val updated = existing.copy(
                description = parsed.description,
                version = bundledVersion,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
            )
            db.execSQL(
                "UPDATE skills SET description=?, version=?, updated_at=? WHERE id=?",
                arrayOf<Any>(updated.description, bundledVersion, updated.updatedAt, bundledId)
            )
            writeSkillMd(updated)
            mutateSkills { list -> list.map { if (it.id == bundledId) updated else it } }
            Log.i(TAG, "Upgraded bundled skill: $bundledId → v$bundledVersion")
        } else {
            add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                source = ImportSource.BUNDLED,
            )
            Log.i(TAG, "Installed bundled skill: $bundledId (v$bundledVersion)")
        }
    }


    fun reloadFromDisk() {
        requestReload("reloadFromDisk", force = true)
    }

    fun requestReload(reason: String, force: Boolean = false) {
        if (force) forceReloadPending.set(true)
        reloadRequests.trySend(reason)
    }

    private fun startReloadWorker() {
        backgroundScope.launch {
            for (reason in reloadRequests) {
                val force = forceReloadPending.getAndSet(false)
                runCatching {
                    val signature = SkillDiskSignature.compute(skillsDir)
                    if (!force && signature == lastDiskSignature) return@runCatching
                    val started = System.currentTimeMillis()
                    loadAllCommitted()
                    lastDiskSignature = signature
                    Log.i(TAG, "reload reason=$reason force=$force skills=${_skills.value.size} tookMs=${System.currentTimeMillis() - started}")
                }.onFailure {
                    Log.e(TAG, "background reload failed reason=$reason: ${it.message}", it)
                }
            }
        }
    }


    private inline fun mutateSkills(transform: (List<Skill>) -> List<Skill>) {
        synchronized(skillsLock) {
            _skills.value = transform(_skills.value)
            mutationSeq++
        }
    }

    private fun loadAllCommitted() {
        repeat(3) { if (loadAll(requireSeq = true)) return }
        loadAll(requireSeq = false)
    }

    private fun loadAll(requireSeq: Boolean = false): Boolean {
        val seqAtStart = synchronized(skillsLock) { mutationSeq }
        val dbSkills = mutableListOf<Skill>()
        val storageReady = skillsDir.isDirectory
        val onDisk = SkillDiskSignature.skillIdsOnDisk(skillsDir)
        val customIds = mutableListOf<String>()
        db.rawQuery("SELECT id, import_source FROM skills", null).use { c ->
            while (c.moveToNext()) {
                if (ImportSource.from(c.getString(1)) != ImportSource.BUNDLED) customIds.add(c.getString(0))
            }
        }
        val missingCustom = customIds.count { it !in onDisk }
        val pruneAllowed = storageReady && !SkillDiskSignature.breakerTripped(customIds.size, missingCustom)
        if (missingCustom > 0 && !pruneAllowed) {
            if (!storageReady) {
                Log.w(TAG, "skillsDir unready, skipping orphan pruning for $missingCustom skill(s)")
            } else {
                Log.e(TAG, "Circuit breaker tripped ($missingCustom / ${customIds.size} missing). Keeping all orphan rows to prevent mass data wipe.")
            }
        }
        db.rawQuery("SELECT * FROM skills ORDER BY installed_at DESC", null).use { cursor ->
        while (cursor.moveToNext()) {
            try {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val importSource = ImportSource.from(cursor.getString(cursor.getColumnIndexOrThrow("import_source")))
            if (importSource != ImportSource.BUNDLED && id !in onDisk && pruneAllowed) {
                db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
                db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
                Log.i(TAG, "Pruned orphan skill row (no SKILL.md on disk): $id")
                continue
            }
            val body = readSkillMdBody(id)
            val sourceUrlIdx = cursor.getColumnIndex("source_url")
            val useCountIdx = cursor.getColumnIndex("use_count")
            var description = cursor.getString(cursor.getColumnIndexOrThrow("description"))
            var name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            var version = cursor.getString(cursor.getColumnIndexOrThrow("version"))
            val nameStale = name.isBlank()
            val descStale = description == ">" || description == "|" || description.isBlank()
            if (descStale || nameStale) {
                val skillMd = File(skillsDir, "$id/SKILL.md")
                if (skillMd.exists()) {
                    val reparsed = parseSkillMd(runCatching { skillMd.readText() }.getOrNull() ?: "")
                    if (reparsed != null) {
                        var changed = false
                        if (descStale && reparsed.description.isNotBlank()) {
                            description = reparsed.description
                            changed = true
                        }
                        if (nameStale && reparsed.name.isNotBlank()) {
                            name = reparsed.name
                            changed = true
                        }
                        if (version.isBlank()) {
                            version = reparsed.version
                            changed = true
                        }
                        if (changed) {
                            db.execSQL(
                                "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
                                arrayOf<Any>(name, description, version, System.currentTimeMillis(), id),
                            )
                            Log.i(TAG, "Self-healed skill name/description for $id")
                        }
                    }
                }
            }
            dbSkills.add(Skill(
                id = id,
                name = name,
                description = description,
                version = version,
                importSource = importSource,
                isEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) == 1,
                installedAt = cursor.getLong(cursor.getColumnIndexOrThrow("installed_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                body = body,
                sourceURL = if (sourceUrlIdx >= 0 && !cursor.isNull(sourceUrlIdx)) cursor.getString(sourceUrlIdx) else null,
                useCount = if (useCountIdx >= 0) cursor.getDouble(useCountIdx) else 0.0,
            ))
            } catch (t: Throwable) {
                Log.e(TAG, "skipping unreadable skill row: ${t.message}", t)
            }
        }
        }

        val known = dbSkills.mapTo(HashSet(dbSkills.size)) { it.id }
        for (dirName in onDisk) {
            val dir = File(skillsDir, dirName)
            val skillMd = File(dir, "SKILL.md")
            if (dirName !in known) {
                val parsed = runCatching { parseSkillMd(skillMd.readText()) }.getOrNull()
                if (parsed != null) {
                    val skill = Skill(
                        id = dir.name,
                        name = parsed.name,
                        description = parsed.description,
                        importSource = ImportSource.SESSION,
                        body = parsed.body,
                    )
                    insertDb(skill)
                    dbSkills.add(skill)
                    Log.i(TAG, "Auto-discovered skill: ${dir.name}")
                }
            }
        }

        synchronized(skillsLock) {
            if (requireSeq && mutationSeq != seqAtStart) return false
            _skills.value = dbSkills
        }
        return true
    }

    private fun insertDb(skill: Skill) {
        db.execSQL(
            """INSERT OR REPLACE INTO skills (id, name, description, version, import_source, source_url, is_enabled, installed_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                skill.id, skill.name, skill.description, skill.version,
                skill.importSource.value, skill.sourceURL,
                if (skill.isEnabled) 1 else 0,
                skill.installedAt, skill.updatedAt,
            )
        )
    }

    private fun writeSkillMd(skill: Skill) {
        val dir = File(skillsDir, skill.id)
        dir.mkdirs()
        val content = buildString {
            appendLine("---")
            appendLine("name: ${skill.name}")
            appendLine("description: ${skill.description}")
            appendLine("version: ${skill.version}")
            appendLine("---")
            append(skill.body)
        }
        File(dir, "SKILL.md").writeText(content)
    }

    private fun readSkillMdBody(id: String): String {
        val file = File(skillsDir, "$id/SKILL.md")
        if (!file.exists()) return ""
        val parsed = parseSkillMd(file.readText())
        return parsed?.body ?: ""
    }

    data class ParsedSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val body: String,
    )

    fun parseSkillMdPublic(content: String): ParsedSkill? = parseSkillMd(content)

    private fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")


    private class SkillDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE skills (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    version TEXT NOT NULL DEFAULT '1.0.0',
                    import_source TEXT NOT NULL DEFAULT 'file',
                    source_url TEXT,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    installed_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    use_count REAL NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("""
                CREATE TABLE session_skill_overrides (
                    session_id TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, skill_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN source_url TEXT")
                } catch (_: Exception) {  }
            }
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN use_count REAL NOT NULL DEFAULT 0")
                } catch (_: Exception) {  }
            }
        }
    }
}

private val SKILL_CREATOR_CONTENT = """
---
name: skill-creator
version: 2.0.0
description: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Claude's capabilities with specialized knowledge, workflows, or tool integrations.
---

# Skill Creator

This skill provides guidance for creating effective skills.

## About Skills

Skills are modular, self-contained packages that extend Claude's capabilities by providing
specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific
domains or tasks—they transform Claude from a general-purpose agent into a specialized agent
equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key

The context window is a public good. Skills share the context window with everything else Claude needs: system prompt, conversation history, other Skills' metadata, and the actual user request.

**Default assumption: Claude is already very smart.** Only add context Claude doesn't already have. Challenge each piece of information: "Does Claude really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility and variability:

- **High freedom (text-based instructions)**: Use when multiple approaches are valid.
- **Medium freedom (pseudocode or scripts with parameters)**: Use when a preferred pattern exists.
- **Low freedom (specific scripts, few parameters)**: Use when operations are fragile, consistency is critical, or a specific sequence must be followed.

### Anatomy of a Skill

Every skill consists of a required SKILL.md file and optional bundled resources:

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name + description required)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/       - Executable code
    ├── references/    - Documentation loaded as needed
    └── assets/        - Files used in output (templates, icons, etc.)
```

#### SKILL.md Frontmatter

- `name` (required): The skill name
- `description` (required): What the skill does and when to trigger it. Be comprehensive—this is the primary triggering mechanism.

#### SKILL.md Body

Instructions and guidance, loaded after the skill triggers. Keep under 500 lines; split into reference files when approaching this limit.

### Progressive Disclosure

Skills use three loading levels:
1. **Metadata** - Always in context (~100 words)
2. **SKILL.md body** - When skill triggers (<5k words)
3. **Bundled resources** - As needed (unlimited)

## Skill Creation Process

1. **Understand** the skill with concrete examples from the user
2. **Plan** reusable contents (scripts, references, assets)
3. **Create** the SKILL.md with proper frontmatter and instructions
4. **Test** by using the skill on real tasks
5. **Iterate** based on actual usage

### Writing the SKILL.md

- Use imperative/infinitive form
- `description` field should include all "when to use" triggers (body is loaded after triggering)
- Only add context Claude doesn't already have
- Prefer concise examples over verbose explanations
- Keep essential workflow in SKILL.md; move detailed reference material to separate files

### What NOT to Include

Do not create extraneous files: README.md, INSTALLATION_GUIDE.md, CHANGELOG.md, etc. The skill should only contain what an AI agent needs to do the job.
""".trimIndent()
