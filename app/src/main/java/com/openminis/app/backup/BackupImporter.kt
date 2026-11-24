package com.openminis.app.backup

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.backup.BackupRecordMapper.bool
import com.openminis.app.backup.BackupRecordMapper.int
import com.openminis.app.backup.BackupRecordMapper.millis
import com.openminis.app.backup.BackupRecordMapper.str
import com.openminis.app.backup.BackupRecordMapper.unwrapNested
import com.openminis.app.logging.AppLogger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class BackupImporter(
    private val context: Context,
    private val db: AppDatabase,
) {

    data class Options(
        val categories: Set<BackupCategory>? = null,
        val skipIntegrityCheck: Boolean = false,
        val passphrase: String? = null,
    )

    data class CategoryReport(
        val category: String,
        var imported: Int = 0,
        var updated: Int = 0,
        var skipped: Int = 0,
        var unreadable: Int = 0,
        var filesWritten: Int = 0,
        var bytesWritten: Long = 0,
        var sizeSkippedInPackage: Int = 0,
        var notDownloadedInPackage: Int = 0,
        var missingBlobs: Int = 0,
        var rejectedPaths: Int = 0,
        var credentialsRestored: Int = 0,
        var credentialsKept: Int = 0,
        var failed: String? = null,
        var declaredAccounted: Int? = null,
        val warnings: MutableList<String> = mutableListOf(),
    )

    data class Progress(
        val categoryKey: String,
        val done: Int = 0,
        val total: Int? = null,
    )

    data class Report(
        val backupId: String,
        val createdAt: String?,
        val sourcePlatform: String?,
        val categories: MutableList<CategoryReport> = mutableListOf(),
        var integrityChecked: Int = 0,
        var integrityFailed: List<String> = emptyList(),
        var wasEncrypted: Boolean = false,
        val warnings: MutableList<String> = mutableListOf(),
        var durationMillis: Long = 0,
    ) {
        val totalImported: Int get() = categories.sumOf { it.imported }
        val totalUpdated: Int get() = categories.sumOf { it.updated }
        val totalSkipped: Int get() = categories.sumOf { it.skipped }
        val totalMissingBlobs: Int get() = categories.sumOf { it.missingBlobs }
    }

    suspend fun import(
        packageRoot: File,
        options: Options = Options(),
        onProgress: ((String) -> Unit)? = null,
        onCount: ((Progress) -> Unit)? = null,
    ): Report = activityLock.withLock {
        importBody(packageRoot, options, onProgress, onCount)
    }

    private suspend fun importBody(
        extractedRoot: File,
        options: Options,
        onProgress: ((String) -> Unit)?,
        onCount: ((Progress) -> Unit)?,
    ): Report {
        val started = System.currentTimeMillis()
        val root = BackupZip.packageRoot(extractedRoot)
        val reader = BackupPackageReader(root)
        val manifest = reader.readManifest()

        val report = Report(
            backupId = manifest.backupId,
            createdAt = manifest.createdAt.takeIf { it.isNotEmpty() },
            sourcePlatform = manifest.app.platform,
        )

        reader.assertNoUndeclaredEncryption(manifest)

        var keys: BackupCrypto.Keys? = null
        if (manifest.encryption != null) {
            val passphrase = options.passphrase
            if (passphrase.isNullOrEmpty()) {
                throw BackupException("This backup is encrypted. Enter its passphrase to restore.")
            }
            onProgress?.invoke("Checking passphrase…")
            keys = reader.unlock(passphrase, manifest)
            report.wasEncrypted = true
        }

        try {
            if (!options.skipIntegrityCheck) {
                onProgress?.invoke(context.getString(com.openminis.app.R.string.restore_progress_verifying))
                val failures = reader.verifyIntegrity(manifest)
                report.integrityChecked = manifest.integrity.size
                report.integrityFailed = failures
                if (failures.isNotEmpty()) {
                    throw BackupException(
                        "Integrity check failed for ${failures.size} file(s): " +
                            failures.take(3).joinToString(", ")
                    )
                }
            }

            val work = if (keys != null) {
                onProgress?.invoke(context.getString(com.openminis.app.R.string.restore_progress_decrypting))
                decryptMembers(root, keys)
            } else {
                root
            }

            val fileIndex = readFileIndex(work)
            val wanted = options.categories
                ?: manifest.categories.keys.mapNotNull(BackupCategory::fromKey).toSet()

            for (category in ORDER.filter { it in wanted }) {
                coroutineContext.ensureActive()
                onProgress?.invoke(
                    context.getString(
                        com.openminis.app.R.string.backup_restoring_category,
                        context.getString(com.openminis.app.ui.settings.backup.categoryNameRes(category)),
                    ),
                )
                val total = manifest.categories[category.key]?.entries?.takeIf { it > 0 }
                onCount?.invoke(Progress(category.key, 0, total))
                val report0: (Int) -> Unit = { done ->
                    onCount?.invoke(Progress(category.key, done, total))
                }
                val categoryReport = try {
                    when (category) {
                        BackupCategory.CHATS -> importChats(work, fileIndex, report0)
                        BackupCategory.SHARED_FILES -> importSharedFiles(work, fileIndex)
                        BackupCategory.SKILLS -> importSkills(work, fileIndex)
                        BackupCategory.MEMORY -> importMemory(work)
                        BackupCategory.MCP_SERVERS -> importMcpServers(work)
                        BackupCategory.PROVIDERS -> importProviders(work)
                        BackupCategory.ENVIRONMENT_VARIABLES -> importEnvironmentVariables(work)
                        else -> null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "[Restore] category ${category.key} failed: ${e.message}")
                    CategoryReport(category.key, failed = e.message ?: e.toString())
                }
                categoryReport?.let {
                    report.categories.add(it)
                    report.warnings.addAll(it.warnings)
                    val declared = total?.toString() ?: "?"
                    AppLogger.info(
                        TAG,
                        "[Restore] ${it.category}: imported=${it.imported} " +
                            "updated=${it.updated} skipped=${it.skipped} " +
                            "unreadable=${it.unreadable} declared=$declared " +
                            "files=${it.filesWritten} missingBlobs=${it.missingBlobs}" +
                            (it.failed?.let { f -> " FAILED=$f" } ?: "")
                    )
                    shortfallWarning(it, total)?.let { line -> AppLogger.warning(TAG, line) }
                }
            }

            if (report.totalMissingBlobs > 0) {
                report.warnings.add(
                    "${report.totalMissingBlobs} file(s) were listed in the backup but their " +
                        "content was missing from the package."
                )
            }
            report.durationMillis = System.currentTimeMillis() - started
            AppLogger.info(
                TAG,
                "[Restore] done id=${manifest.backupId} imported=${report.totalImported} " +
                    "updated=${report.totalUpdated} skipped=${report.totalSkipped} " +
                    "in ${report.durationMillis}ms"
            )
            return report
        } finally {
            keys?.destroy()
        }
    }


    private suspend fun importChats(
        root: File,
        fileIndex: List<BackupFileIndexEntry>,
        onCount: ((Int) -> Unit)? = null,
    ): CategoryReport {
        val report = CategoryReport(BackupCategory.CHATS.key)
        val dao = db.chatDao()
        val dataDir = File(root, "data")

        readJsonl(dataDir, "folders") { rec ->
            val f = rec.obj ?: return@readJsonl
            val id = f.str("id") ?: return@readJsonl
            when (val d = BackupRecordMapper.folder(f, dao.getFolder(id))) {
                is BackupRecordMapper.Decoded.Apply -> {
                    dao.insertFolder(d.entity)
                    if (d.isNew) report.imported += 1 else report.updated += 1
                }
                is BackupRecordMapper.Decoded.Stale -> report.skipped += 1
                BackupRecordMapper.Decoded.Unreadable -> Unit
            }
        }

        val restoredSessionIds = mutableSetOf<String>()
        val newlyInsertedSessionIds = mutableListOf<String>()
        val nowMs = System.currentTimeMillis()
        var sessionsSeen = 0
        db.withTransaction {
        readJsonl(dataDir, "sessions") { rec ->
            sessionsSeen += 1
            if (sessionsSeen % 100 == 0) coroutineContext.ensureActive()
            val envelope = rec.obj ?: return@readJsonl
            val s = envelope.unwrapNested("session")
            val id = s.str("id")
            if (id == null) {
                report.unreadable += 1
                return@readJsonl
            }
            val restored = when (val d = BackupRecordMapper.session(envelope, dao.getSession(id))) {
                is BackupRecordMapper.Decoded.Apply -> {
                    dao.insertSession(d.entity)
                    if (d.isNew) report.imported += 1 else report.updated += 1
                    if (d.isNew) newlyInsertedSessionIds.add(id)
                    restoredSessionIds.add(id)
                    d.entity
                }
                is BackupRecordMapper.Decoded.Stale -> {
                    report.skipped += 1
                    restoredSessionIds.add(id)
                    return@readJsonl
                }
                BackupRecordMapper.Decoded.Unreadable -> {
                    report.unreadable += 1
                    return@readJsonl
                }
            }
            val incomingUpdated = restored.updatedAt
            if (nowMs - incomingUpdated < XSESSION_DIAG_AUTO_WINDOW_MS) {
                AppLogger.info(
                    TAG,
                    "[XSessionDiag] restore/session: id=${id.take(8)} " +
                        "title=${restored.title?.take(24)} " +
                        "backupUpdatedAt=$incomingUpdated restoreNow=$nowMs " +
                        "ageAtRestoreMs=${nowMs - incomingUpdated} " +
                        "(inside 15min auto-resume window -> can be auto-resumed as 'new chat')",
                )
            }
        }

        AppLogger.info(
            TAG,
            "[Restore] chats: sessions.jsonl held $sessionsSeen record(s); " +
                "${restoredSessionIds.size} usable as message parents"
        )

        }

        try {
        // The manifest counts MESSAGES for the chats category, so this is the
        // loop whose progress the button reports.
        var seen = 0
        var orphanedMessages = 0
        val orphanSessionIds = mutableSetOf<String>()
        db.withTransaction {
        readJsonl(dataDir, "messages") { rec ->
            if (++seen % 200 == 0) {
                onCount?.invoke(seen)
                coroutineContext.ensureActive()
            }
            val m = rec.obj ?: return@readJsonl
            val message = BackupRecordMapper.message(m)
            if (message == null) {
                report.unreadable += 1
                return@readJsonl
            }
            val sessionId = message.sessionId
            if (sessionId !in restoredSessionIds) {
                orphanedMessages += 1
                orphanSessionIds.add(sessionId)
                report.skipped += 1
                return@readJsonl
            }
            dao.insertMessage(message)
            report.imported += 1
        }
        }

        if (orphanedMessages > 0) {
            AppLogger.warning(
                TAG,
                "[Restore] chats: dropped $orphanedMessages message(s) whose parent session " +
                    "was not in the database, across ${orphanSessionIds.size} distinct " +
                    "session(s). Sample: " +
                    orphanSessionIds.take(5).joinToString(", ") { it.take(8) }
            )
        }

        db.withTransaction {
        readJsonl(dataDir, "compact_markers") { rec ->
            val c = rec.obj ?: return@readJsonl
            val marker = BackupRecordMapper.compactMarker(c) ?: return@readJsonl
            val sessionId = marker.sessionId
            if (sessionId !in restoredSessionIds) {
                report.skipped += 1
                return@readJsonl
            }
            runCatching {
                dao.insertCompactMarker(marker)
                report.imported += 1
            }.onFailure { report.skipped += 1 }
        }
        }

        db.withTransaction {
            for (sid in restoredSessionIds) {
                val parts = dao.lastMessageParts(sid) ?: continue
                val preview = ChatRepository.extractTextPreview(parts) ?: continue
                val s = dao.getSession(sid) ?: continue
                dao.updateLastMessage(sid, preview, s.updatedAt)
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val removed = withContext(NonCancellable) {
                deleteEmptySessions(
                    newlyInsertedSessionIds,
                    messageCount = { dao.messageCountForSession(it) },
                    delete = { dao.deleteSession(it) },
                )
            }
            AppLogger.info(
                TAG,
                "[Restore] chats: cancelled — removed ${removed.size} of " +
                    "${newlyInsertedSessionIds.size} newly-inserted session(s) left without messages"
            )
            throw e
        }

        val sessionsRoot = File(context.filesDir, "minis-sessions")
        val files = BackupRestoreFiles.restore(
            packageRoot = root,
            fileIndex = fileIndex,
            category = BackupCategory.CHATS,
            containmentRoot = sessionsRoot,
        ) { path ->
            val parts = path.split('/')
            if (parts.size < 3 || parts[0] != "chats") null
            else File(sessionsRoot, parts.drop(1).joinToString("/"))
        }
        applyFileResult(report, files)

        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }.onFailure {
            AppLogger.warning(TAG, "[Restore] WAL checkpoint failed: ${it.message}")
        }
        return report
    }


    // MARK: - Shared files / Skills / Memory / MCP
    private fun importSharedFiles(
        root: File,
        fileIndex: List<BackupFileIndexEntry>,
    ): CategoryReport {
        val report = CategoryReport(BackupCategory.SHARED_FILES.key)
        val dest = File(context.filesDir, "minis-global/shared")
        val files = BackupRestoreFiles.restore(
            root, fileIndex, BackupCategory.SHARED_FILES, dest
        ) { path ->
            if (!path.startsWith("shared/")) null
            else File(dest, path.removePrefix("shared/"))
        }
        applyFileResult(report, files)
        return report
    }

    private fun importSkills(root: File, fileIndex: List<BackupFileIndexEntry>): CategoryReport {
        val report = CategoryReport(BackupCategory.SKILLS.key)
        val dest = File(context.filesDir, "minis-global/skills")
        val files = BackupRestoreFiles.restore(root, fileIndex, BackupCategory.SKILLS, dest) { path ->
            if (!path.startsWith("skills/")) null
            else File(dest, path.removePrefix("skills/"))
        }
        applyFileResult(report, files)
        runCatching {
            (context.applicationContext as? com.openminis.app.MinisApp)
                ?.skillRepository?.requestReload("backup_restore", force = true)
        }
        return report
    }

    private fun importMemory(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.MEMORY.key)
        val src = File(root, "data/memory")
        if (!src.isDirectory) return report
        val dest = File(context.filesDir, "minis-global/memory").apply { mkdirs() }
        val destRoot = dest.canonicalFile
        for (file in src.walkTopDown().filter { it.isFile }) {
            val rel = file.relativeTo(src).path.replace(File.separatorChar, '/')
            val out = File(dest, rel)
            val parent = out.parentFile?.let { it.mkdirs(); it.canonicalFile }
            if (parent == null || !(parent.path == destRoot.path ||
                    parent.path.startsWith(destRoot.path + File.separator))
            ) {
                report.rejectedPaths += 1
                continue
            }
            file.copyTo(out, overwrite = true)
            report.filesWritten += 1
            report.bytesWritten += file.length()
        }
        return report
    }

    private fun importMcpServers(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.MCP_SERVERS.key)
        val src = File(root, "data/mcp_servers.json")
        if (!src.isFile) return report
        val dest = File(context.filesDir, "minis-global/mcp-servers/servers.json")
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        report.filesWritten = 1
        report.bytesWritten = src.length()
        report.imported = 1
        return report
    }


    private val app: com.openminis.app.MinisApp?
        get() = context.applicationContext as? com.openminis.app.MinisApp

    private suspend fun importProviders(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.PROVIDERS.key)
        val repo = app?.providerRepositoryOrNull ?: run {
            report.failed = "provider repository unavailable"
            return report
        }
        val configFile = File(root, "data/provider_config.json")
        if (!configFile.isFile) return report

        val parsed = try {
            parseProviderConfigLeniently(configFile.readText())
        } catch (e: Exception) {
            report.unreadable += 1
            AppLogger.error(TAG, "[Restore] provider_config.json unreadable: ${e::class.simpleName}: ${e.message}")
            report.warnings += providerConfigWarnings(null, e)
            return report
        }
        val config = parsed.config
        report.unreadable += parsed.droppedInstances
        report.warnings += providerConfigWarnings(parsed, null)
        if (parsed.droppedInstances > 0) {
            AppLogger.warning(
                TAG,
                "[Restore] provider_config.json: dropped ${parsed.droppedInstances} " +
                    "undecodable instance(s), kept ${config.instances.size}",
            )
        }

        val merge = repo.mergeBackupProviderConfig(config)
        val groupIdRewrites = merge.groupIdRewrites
        if (groupIdRewrites.isNotEmpty()) {
            rewriteSessionGroupBindings(groupIdRewrites)
            rewriteScheduledTaskGroupBindings(groupIdRewrites)
        }
        report.declaredAccounted = config.instances.map { it.id }.distinct().size

        val ruleRecords = readJsonlList(File(root, "data"), "thinking_rules").mapNotNull { env ->
            env.obj?.let {
                runCatching {
                    BackupFormat.json.decodeFromJsonElement(
                        BackupThinkingRuleRecord.serializer(), it
                    )
                }.getOrNull()
            }
        }
        if (ruleRecords.isNotEmpty()) {
            val (written, skipped) = repo.restoreBackupThinkingRules(ruleRecords)
            report.imported += written
            report.skipped += skipped
        }

        val agents = readJsonlList(File(root, "data"), BackupSubAgentMapping.FILE_BASE).mapNotNull { env ->
            env.obj?.let {
                runCatching {
                    BackupFormat.json.decodeFromJsonElement(BackupSubAgentRecord.serializer(), it)
                }.getOrNull()?.let { r -> BackupSubAgentMapping.fromRecord(r) }
            }
        }.let { BackupProviderMerge.rewriteSubAgents(it, groupIdRewrites) }
        if (agents.isNotEmpty()) {
            val (written, skipped) = repo.restoreBackupSubAgents(agents)
            report.imported += written
            report.skipped += skipped
            AppLogger.info(TAG, "[Restore] sub agents: $written applied, $skipped skipped (local newer, unchanged or same name)")
        }

        val secrets = readSecrets(root)
        val credentialsWritten = mutableSetOf<String>()
        if (secrets != null) {
            var restored = 0
            var kept = 0
            for (s in secrets.providers) {
                if (repo.restoreBackupProviderSecret(s)) {
                    restored++
                    credentialsWritten += s.instanceId
                } else kept++
            }
            report.credentialsRestored = restored
            report.credentialsKept = kept
        }

        merge.merge?.let { m ->
            val c = BackupProviderMerge.counts(m, config.instances.map { it.id }, credentialsWritten)
            report.imported += c.imported
            report.updated += c.updated
            report.skipped += c.skipped
            AppLogger.info(
                TAG,
                "[Restore] providers counted: instances new=${m.instancesAdded.size} " +
                    "changed=${c.updated - m.groupsChanged} upToDate=${c.skipped} " +
                    "credentialsWritten=${credentialsWritten.size} groups new=${m.groupsAdded} " +
                    "changed=${m.groupsChanged} (mergedByName=${m.groupIdRewrites.size})",
            )
        }
        return report
    }

    private suspend fun rewriteSessionGroupBindings(rewrites: Map<String, String>) {
        val dao = db.chatDao()
        var n = 0
        for (from in rewrites.keys) {
            for (s in dao.sessionsWithBindingLike(from)) {
                val next = BackupProviderMerge.rewriteBindingJson(s.modelBinding, rewrites)
                if (next != null && next != s.modelBinding) {
                    dao.updateSessionBindingOnly(s.id, next)
                    n++
                }
            }
        }
        AppLogger.info(TAG, "[Restore] session bindings rewritten to merged groups: $n")
    }

    private fun rewriteScheduledTaskGroupBindings(rewrites: Map<String, String>) {
        val store = com.openminis.app.scheduled.ScheduledTaskStore(context)
        var n = 0
        for (t in store.all()) {
            val binding = BackupProviderMerge.rewriteBindingJson(t.modelBinding, rewrites)
            val modelId = BackupProviderMerge.rewriteLegacyModelId(t.modelId, rewrites)
            if (binding == t.modelBinding && modelId == t.modelId) continue
            store.update(t.id) { cur ->
                cur.copy(
                    modelBinding = BackupProviderMerge.rewriteBindingJson(cur.modelBinding, rewrites),
                    modelId = BackupProviderMerge.rewriteLegacyModelId(cur.modelId, rewrites),
                )
            }
            n++
        }
        AppLogger.info(TAG, "[Restore] scheduled tasks rewritten to merged groups: $n")
    }

    private fun importEnvironmentVariables(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.ENVIRONMENT_VARIABLES.key)
        val repo = app?.let { if (it.subsystemsReady()) it.envVarRepository else null } ?: run {
            report.failed = "env-var repository unavailable"
            return report
        }
        val metaFile = File(root, "data/env_vars.json")
        if (!metaFile.isFile) return report

        val metas = try {
            BackupFormat.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(BackupEnvVarMeta.serializer()),
                metaFile.readText(),
            )
        } catch (e: Exception) {
            report.unreadable += 1
            return report
        }

        val valuesByName = readSecrets(root)?.envVars?.associate { s ->
            s.name to runCatching {
                String(android.util.Base64.decode(s.value, android.util.Base64.NO_WRAP))
            }.getOrNull()
        } ?: emptyMap()

        for (meta in metas) {
            val restored = valuesByName[meta.key]
            val existing = repo.entries.value.firstOrNull { it.key.equals(meta.key, ignoreCase = true) }

            if (existing != null) {
                val current = repo.getValue(existing.key)
                when {
                    !current.isNullOrEmpty() -> report.skipped += 1
                    restored.isNullOrEmpty() -> report.skipped += 1
                    repo.update(existing.id, existing.key, restored, existing.note) -> report.imported += 1
                    else -> report.skipped += 1
                }
                continue
            }

            if (restored.isNullOrEmpty()) {
                report.skipped += 1
                continue
            }
            if (repo.add(meta.key, restored, meta.note, enabled = meta.enabled)) report.imported += 1
            else report.skipped += 1
        }
        return report
    }

    private fun readSecrets(root: File): BackupSecrets? {
        val f = File(root, "secrets.json")
        if (!f.isFile) return null
        return runCatching {
            BackupFormat.json.decodeFromString(BackupSecrets.serializer(), f.readText())
        }.getOrNull()
    }

    private fun applyFileResult(report: CategoryReport, files: BackupRestoreFiles.Result) {
        report.filesWritten += files.written
        report.bytesWritten += files.bytes
        report.missingBlobs += files.missingBlobs
        report.sizeSkippedInPackage += files.sizeSkippedInPackage
        report.notDownloadedInPackage += files.notDownloadedInPackage
        report.rejectedPaths += files.rejectedPaths
    }


    private fun decryptMembers(root: File, keys: BackupCrypto.Keys): File {
        val work = File(context.cacheDir, "restore-work").apply {
            deleteRecursively(); mkdirs()
        }
        val base = root.canonicalFile
        for (file in base.walkTopDown().filter { it.isFile }) {
            val rel = file.relativeTo(base).path.replace(File.separatorChar, '/')
            val out = File(work, rel.removeSuffix(".enc")).apply { parentFile?.mkdirs() }
            if (rel.endsWith(".enc")) {
                val logical = rel.removeSuffix(".enc")
                val key = if (logical == "secrets.json") keys.secretsKey else keys.dataKey
                BackupCrypto.decryptFile(file, out, key, rel)
            } else {
                file.copyTo(out, overwrite = true)
            }
        }
        return work
    }

    private fun readFileIndex(root: File): List<BackupFileIndexEntry> {
        val file = File(root, "files.index.jsonl")
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching {
                BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), line)
            }.getOrNull()
        }
    }

    private inline fun readJsonl(dataDir: File, baseName: String, onRecord: (Envelope) -> Unit) {
        val shards = (dataDir.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name == "$baseName.jsonl" ||
                (it.name.startsWith("$baseName-") && it.name.endsWith(".jsonl"))) }
            .sortedBy { it.name }
        for (shard in shards) {
            shard.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val envelope = try {
                        Envelope(
                            BackupFormat.json.parseToJsonElement(line).jsonObject["d"]?.jsonObject
                        )
                    } catch (_: Exception) {
                        continue
                    }
                    onRecord(envelope)
                }
            }
        }
    }

    private fun readJsonlList(dataDir: File, baseName: String): List<Envelope> =
        mutableListOf<Envelope>().also { out -> readJsonl(dataDir, baseName) { out.add(it) } }

    private class Envelope(val obj: JsonObject?)

    companion object {
        private const val TAG = "Restore"

        internal fun shortfallWarning(r: CategoryReport, declared: Int?): String? {
            if (declared == null) return null
            val accounted = r.declaredAccounted ?: (r.imported + r.updated)
            if (accounted >= declared) return null
            val verb = if (r.declaredAccounted != null) "accounted for" else "wrote"
            return "[Restore] ${r.category}: SHORT — package declared $declared, " +
                "$verb $accounted (skipped=${r.skipped}, unreadable=${r.unreadable})"
        }

        internal suspend fun deleteEmptySessions(
            sessionIds: Collection<String>,
            messageCount: suspend (String) -> Int,
            delete: suspend (String) -> Unit,
        ): List<String> {
            val deleted = mutableListOf<String>()
            for (id in sessionIds) {
                val count = runCatching { messageCount(id) }.getOrNull() ?: continue
                if (count != 0) continue
                runCatching { delete(id) }.onSuccess { deleted += id }
            }
            return deleted
        }

        private const val XSESSION_DIAG_AUTO_WINDOW_MS = 15L * 60 * 1000

        internal data class LenientProviderConfig(
            val config: com.openminis.app.data.model.ProviderConfig,
            val droppedInstances: Int,
            val dropReasons: List<String> = emptyList(),
        )

        internal fun providerConfigWarnings(parsed: LenientProviderConfig?, failure: Throwable?): List<String> {
            if (failure != null) {
                val detail = if (failure is ProviderConfigDecodeException) failure.message
                else "${failure::class.simpleName}: ${failure.message?.trim()}"
                return listOf("Providers: provider_config.json could not be read, so no provider was restored ($detail)")
            }
            return parsed?.dropReasons.orEmpty().map { reason ->
                "Providers: a provider in the backup could not be read and was skipped ($reason)"
            }
        }

        internal fun parseProviderConfigLeniently(text: String): LenientProviderConfig {
            val root = BackupFormat.json.parseToJsonElement(text).jsonObject
            val rawInstances = root["instances"] as? kotlinx.serialization.json.JsonArray

            if (rawInstances == null) {
                return LenientProviderConfig(decodeLocatingFailure(root), droppedInstances = 0)
            }

            val knownTypes = com.openminis.app.data.model.ProviderType.entries.map { it.name }.toSet()
            val kept = mutableListOf<kotlinx.serialization.json.JsonElement>()
            var dropped = 0
            val reasons = mutableListOf<String>()
            for (element in rawInstances) {
                val obj = element as? JsonObject ?: run {
                    dropped++
                    reasons.add("an entry of instances is not an object")
                    null
                } ?: continue
                val rawType = (obj["providerType"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull
                val normalized = if (rawType != null && rawType !in knownTypes) {
                    AppLogger.warning(
                        TAG,
                        "[Restore] provider instance ${obj["id"]?.jsonPrimitive?.contentOrNull}: " +
                            "unknown providerType '$rawType' — importing as unsupported",
                    )
                    JsonObject(
                        obj + ("providerType" to kotlinx.serialization.json.JsonPrimitive(
                            com.openminis.app.data.model.ProviderType.unsupported.name,
                        )),
                    )
                } else obj
                val decodes = runCatching {
                    BackupFormat.json.decodeFromJsonElement(
                        com.openminis.app.data.model.ProviderInstance.serializer(), normalized,
                    )
                }
                if (decodes.isSuccess) {
                    kept.add(normalized)
                } else {
                    dropped++
                    val e = decodes.exceptionOrNull()
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "?"
                    reasons.add("instance $id: ${e?.let { it::class.simpleName }}: ${e?.message?.trim()}")
                    AppLogger.warning(
                        TAG,
                        "[Restore] provider instance dropped (undecodable): ${reasons.last()}",
                    )
                }
            }

            val patched = JsonObject(root + ("instances" to kotlinx.serialization.json.JsonArray(kept)))
            return LenientProviderConfig(
                decodeLocatingFailure(patched),
                droppedInstances = dropped,
                dropReasons = reasons,
            )
        }

        internal class ProviderConfigDecodeException(val path: String?, cause: Throwable) : Exception(
            "${cause::class.simpleName}: ${cause.message?.trim()}" + (path?.let { " (at $it)" } ?: ""),
            cause,
        )

        private fun decodeLocatingFailure(root: JsonObject): com.openminis.app.data.model.ProviderConfig =
            try {
                BackupFormat.json.decodeFromJsonElement(com.openminis.app.data.model.ProviderConfig.serializer(), root)
            } catch (e: Exception) {
                throw ProviderConfigDecodeException(locateFailure(root), e)
            }

        private fun locateFailure(root: JsonObject): String? {
            val sections = listOf(
                "modelEntries" to com.openminis.app.data.model.ModelEntry.serializer(),
                "modelGroups" to com.openminis.app.data.model.ModelGroup.serializer(),
                "subAgents" to com.openminis.app.data.model.SubAgentDefinition.serializer(),
            )
            for ((key, serializer) in sections) {
                val items = root[key] as? kotlinx.serialization.json.JsonArray ?: continue
                items.forEachIndexed { index, item ->
                    if (runCatching { BackupFormat.json.decodeFromJsonElement(serializer, item) }.isFailure) {
                        return "$.$key[$index]"
                    }
                }
            }
            return null
        }

        private val activityLock = Mutex()

        private val ORDER = listOf(
            BackupCategory.CHATS,
            BackupCategory.SHARED_FILES,
            BackupCategory.SKILLS,
            BackupCategory.MEMORY,
            BackupCategory.MCP_SERVERS,
            BackupCategory.PROVIDERS,
            BackupCategory.ENVIRONMENT_VARIABLES,
        )
    }
}
