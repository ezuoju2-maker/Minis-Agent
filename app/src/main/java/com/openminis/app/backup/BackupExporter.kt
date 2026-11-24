package com.openminis.app.backup

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class BackupExporter(
    private val context: Context,
    private val db: AppDatabase,
) {

    data class Options(
        val categories: Set<BackupCategory> = BackupCategory.backupable.toSet(),
        val maxFileBytes: Long? = null,
        val includeCredentials: Boolean = true,
        val passphrase: String? = null,
        val snapshotAtMillis: Long = System.currentTimeMillis(),
    )

    data class Summary(
        val packageFile: File,
        val backupId: String,
        val totalBytes: Long,
        val categories: Map<String, BackupManifest.CategoryStat>,
        val skippedFiles: Int,
        val skippedBytes: Long,
        val skippedPaths: List<BackupBlobStore.SkippedPath>,
        val durationMillis: Long,
    )

    suspend fun export(
        options: Options = Options(),
        onProgress: ((String) -> Unit)? = null,
    ): Summary = activityLock.withLock { exportBody(options, onProgress) }

    private suspend fun exportBody(options: Options, onProgress: ((String) -> Unit)?): Summary {
        val started = System.currentTimeMillis()
        val backupId = UUID.randomUUID().toString()
        val deviceName = com.openminis.app.data.DeviceIdentity.displayName(context)

        val staging = File(context.filesDir, "backup-staging/$backupId")
        staging.mkdirs()
        try {
            val dataDir = File(staging, "data").apply { mkdirs() }
            val blobStore = BackupBlobStore(staging, options.maxFileBytes)
            val stats = mutableMapOf<String, BackupManifest.CategoryStat>()

            BackupFileIndexWriter(File(staging, "files.index.jsonl")).use { fileIndex ->
                val runJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                val trees = BackupFileTreeExporter(
                    blobStore, fileIndex, options.snapshotAtMillis,
                    isCancelled = { runJob?.isActive == false },
                )

                if (BackupCategory.CHATS in options.categories) {
                    onProgress?.invoke("Exporting chats…")
                    stats[BackupCategory.CHATS.key] =
                        exportChats(dataDir, trees, options.snapshotAtMillis)
                }
                if (BackupCategory.SHARED_FILES in options.categories) {
                    onProgress?.invoke("Exporting shared files…")
                    stats[BackupCategory.SHARED_FILES.key] = exportSharedFiles(trees)
                }
                if (BackupCategory.SKILLS in options.categories) {
                    onProgress?.invoke("Exporting skills…")
                    stats[BackupCategory.SKILLS.key] = exportSkills(trees)
                }
                if (BackupCategory.MEMORY in options.categories) {
                    onProgress?.invoke("Exporting memory…")
                    stats[BackupCategory.MEMORY.key] = exportMemory(dataDir)
                }
                if (BackupCategory.MCP_SERVERS in options.categories) {
                    onProgress?.invoke("Exporting MCP servers…")
                    exportMcpServers(dataDir)?.let { stats[BackupCategory.MCP_SERVERS.key] = it }
                }
                if (BackupCategory.PROVIDERS in options.categories) {
                    onProgress?.invoke("Exporting providers…")
                    stats[BackupCategory.PROVIDERS.key] =
                        exportProviders(dataDir, options.includeCredentials)
                }
                if (BackupCategory.ENVIRONMENT_VARIABLES in options.categories) {
                    onProgress?.invoke("Exporting environment variables…")
                    exportEnvironmentVariables(dataDir)?.let {
                        stats[BackupCategory.ENVIRONMENT_VARIABLES.key] = it
                    }
                }
            }

            if (options.includeCredentials) {
                onProgress?.invoke("Collecting credentials…")
                exportSecrets(staging, options.categories)
            }

            writeBlobIndex(blobStore.blobIndex, staging)

            var encryption: BackupManifest.Encryption? = null
            var keys: BackupCrypto.Keys? = null
            if (!options.passphrase.isNullOrEmpty()) {
                onProgress?.invoke("Encrypting…")
                val salt = BackupCrypto.makeSalt()
                val kdf = BackupCrypto.currentKDF(salt)
                val derived = BackupCrypto.deriveKeys(options.passphrase, kdf)
                encryptStagedMembers(staging, derived)
                encryption = BackupManifest.Encryption(BackupCrypto.SCHEME, kdf, derived.verifier)
                keys = derived
                stats.replaceAll { _, v -> v.copy(encrypted = true) }
            }

            onProgress?.invoke("Writing manifest…")
            val manifest = buildManifest(backupId, stats, blobStore, staging, encryption, options, deviceName)
            val manifestFile = File(staging, "manifest.json")
            manifestFile.writeText(
                BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest)
            )
            if (keys != null) {
                File(staging, "manifest.mac")
                    .writeText(BackupCrypto.manifestMac(manifestFile.readBytes(), keys.macKey))
                keys.destroy()
            }

            onProgress?.invoke("Packaging…")
            val packageFile = archive(staging, backupId, options.snapshotAtMillis, deviceName, encrypted = encryption != null)
            val duration = System.currentTimeMillis() - started
            AppLogger.info(
                TAG,
                "[Backup] done id=$backupId bytes=${packageFile.length()} " +
                    "skipped=${blobStore.skippedFiles} in ${duration}ms"
            )

            return Summary(
                packageFile = packageFile,
                backupId = backupId,
                totalBytes = packageFile.length(),
                categories = stats,
                skippedFiles = blobStore.skippedFiles,
                skippedBytes = blobStore.skippedBytes,
                skippedPaths = blobStore.skippedPaths.toList(),
                durationMillis = duration,
            )
        } finally {
            staging.deleteRecursively()
            File(context.filesDir, "backup-staging").takeIf {
                it.listFiles()?.isEmpty() == true
            }?.delete()
        }
    }


    private suspend fun exportChats(
        dataDir: File,
        trees: BackupFileTreeExporter,
        snapshotAtMillis: Long,
    ): BackupManifest.CategoryStat {
        val dao = db.chatDao()
        var messageCount = 0
        var fileCount = 0
        var fileBytes = 0L
        var jsonlBytes = 0L

        BackupJsonlWriter(dataDir, "sessions").use { sessions ->
            BackupJsonlWriter(dataDir, "messages").use { messages ->
                BackupJsonlWriter(dataDir, "compact_markers").use { markers ->
                    BackupJsonlWriter(dataDir, "folders").use { folders ->
                        for (session in dao.listSessions()) {
                            if (session.updatedAt > snapshotAtMillis) {
                                AppLogger.info(
                                    TAG, "[Backup] skip session newer than snapshot: ${session.id}"
                                )
                                continue
                            }
                            sessions.write("SessionV2", 1, sessionRecord(session))

                            for (msg in dao.loadMessages(session.id)) {
                                if (msg.createdAt > snapshotAtMillis) continue
                                messages.write("MessageV2", 1, messageRecord(msg))
                                messageCount += 1
                            }
                            for (marker in dao.listCompactMarkers(session.id)) {
                                markers.write("CompactMarkerV2", 1, markerRecord(marker))
                            }

                            val dir = File(context.filesDir, "minis-sessions/${session.id}")
                            val r = trees.export(
                                dir, "chats/${session.id}", BackupCategory.CHATS, session.id
                            )
                            fileCount += r.filesIncluded
                            fileBytes += r.bytesIncluded
                        }

                        for (folder in dao.listFolders()) {
                            folders.write("FolderV2", 1, folderRecord(folder))
                        }
                        jsonlBytes = sessions.totalBytes + messages.totalBytes +
                            markers.totalBytes + folders.totalBytes
                    }
                }
            }
        }

        return BackupManifest.CategoryStat(
            entries = messageCount + fileCount,
            bytes = jsonlBytes + fileBytes,
            encrypted = false,
            messages = messageCount,
            files = fileCount,
        )
    }



    // MARK: - Shared files / Skills / Memory
    private fun exportSharedFiles(trees: BackupFileTreeExporter): BackupManifest.CategoryStat {
        val r = trees.export(
            File(context.filesDir, "minis-global/shared"), "shared", BackupCategory.SHARED_FILES
        )
        return BackupManifest.CategoryStat(r.filesIncluded, r.bytesIncluded, encrypted = false)
    }

    private fun exportSkills(trees: BackupFileTreeExporter): BackupManifest.CategoryStat {
        val root = File(context.filesDir, "minis-global/skills")
        val r = trees.export(root, "skills", BackupCategory.SKILLS)
        return BackupManifest.CategoryStat(
            entries = Companion.skillCount(root),
            bytes = r.bytesIncluded,
            encrypted = false,
            files = r.filesIncluded,
        )
    }

    private fun exportMemory(dataDir: File): BackupManifest.CategoryStat {
        val source = File(context.filesDir, "minis-global/memory")
        val dest = File(dataDir, "memory").apply { mkdirs() }
        var entries = 0
        var bytes = 0L
        if (source.isDirectory) {
            for (file in source.walkTopDown().filter { it.isFile }) {
                val rel = file.relativeTo(source).path.replace(File.separatorChar, '/')
                val out = File(dest, rel).apply { parentFile?.mkdirs() }
                file.copyTo(out, overwrite = true)
                entries += 1
                bytes += file.length()
            }
        }
        return BackupManifest.CategoryStat(entries, bytes, encrypted = false)
    }

    private fun exportMcpServers(dataDir: File): BackupManifest.CategoryStat? {
        val source = File(context.filesDir, "minis-global/mcp-servers/servers.json")
        if (!source.isFile) return null
        val dest = File(dataDir, "mcp_servers.json")
        source.copyTo(dest, overwrite = true)
        return BackupManifest.CategoryStat(mcpServerCount(source), source.length(), encrypted = false)
    }

    private fun mcpServerCount(source: File): Int = Companion.mcpServerCount(source)


    private val app: com.openminis.app.MinisApp?
        get() = context.applicationContext as? com.openminis.app.MinisApp

    private suspend fun exportProviders(
        dataDir: File,
        includeCredentials: Boolean,
    ): BackupManifest.CategoryStat {
        val repo = app?.providerRepositoryOrNull
        val config = repo?.config?.value ?: com.openminis.app.data.model.ProviderConfig()
        val url = File(dataDir, "provider_config.json")
        url.writeText(BackupProviderWire.encode(config))
        var bytes = url.length()

        val ruleCount = exportThinkingRules(dataDir)
        if (ruleCount > 0) {
            bytes += File(dataDir, "thinking_rules.jsonl").length()
        }
        if (exportSubAgents(dataDir, config.subAgents) > 0) {
            bytes += File(dataDir, "${BackupSubAgentMapping.FILE_BASE}.jsonl").length()
        }
        return BackupManifest.CategoryStat(
            entries = config.instances.size,
            bytes = bytes,
            encrypted = false,
            includesCredentials = includeCredentials,
            thinkingRules = ruleCount.takeIf { it > 0 },
        )
    }

    private fun exportSubAgents(
        dataDir: File,
        roster: List<com.openminis.app.data.model.SubAgentDefinition>,
    ): Int {
        val custom = BackupSubAgentMapping.exportable(roster)
        if (custom.isEmpty()) return 0
        BackupJsonlWriter(dataDir, BackupSubAgentMapping.FILE_BASE).use { writer ->
            for (def in custom) {
                writer.write(
                    BackupSubAgentMapping.RECORD_TYPE, 1,
                    BackupFormat.json.encodeToJsonElement(
                        BackupSubAgentRecord.serializer(), BackupSubAgentMapping.toRecord(def),
                    ),
                )
            }
        }
        AppLogger.info(TAG, "[Backup] exported ${custom.size} custom sub agent(s)")
        return custom.size
    }

    private suspend fun exportThinkingRules(dataDir: File): Int {
        val dao = com.openminis.app.data.db.ProviderDatabase
            .getInstance(context).providerConfigDao()
        val rows = dao.loadAllThinkingRules()
        if (rows.isEmpty()) return 0
        BackupJsonlWriter(dataDir, "thinking_rules").use { writer ->
            for (row in rows) {
                val record = BackupThinkingRuleRecord(
                    id = row.id,
                    instanceId = row.providerInstanceId,
                    sortOrder = row.sortOrder,
                    scopeKind = row.scopeKind,
                    scopePattern = row.scopePattern,
                    wireFormatJson = row.wireFormatJson ?: "{}",
                    echoField = null,
                    echoTiming = null,
                    label = row.label,
                    createdAt = null,
                    updatedAt = null,
                )
                writer.write(
                    "ThinkingRuleV1", 1,
                    BackupFormat.json.encodeToJsonElement(
                        BackupThinkingRuleRecord.serializer(), record
                    ),
                )
            }
            AppLogger.info(TAG, "[Backup] exported ${rows.size} custom thinking rule(s)")
            return rows.size
        }
    }

    private fun exportEnvironmentVariables(dataDir: File): BackupManifest.CategoryStat? {
        val repo = app?.let { if (it.subsystemsReady()) it.envVarRepository else null }
        val entries = repo?.entries?.value ?: emptyList()
        if (entries.isEmpty()) return null
        val meta = entries.map {
            BackupEnvVarMeta(id = it.id, key = it.key, note = it.note, createdAt = it.createdAt, enabled = it.enabled)
        }
        val url = File(dataDir, "env_vars.json")
        url.writeText(
            BackupFormat.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(BackupEnvVarMeta.serializer()), meta
            )
        )
        return BackupManifest.CategoryStat(entries.size, url.length(), encrypted = false)
    }

    private fun exportSecrets(staging: File, categories: Set<BackupCategory>) {
        val providerSecrets =
            if (BackupCategory.PROVIDERS in categories) {
                val repo = app?.providerRepositoryOrNull
                repo?.config?.value?.instances.orEmpty()
                    .mapNotNull { repo?.collectBackupProviderSecret(it) }
            } else emptyList()

        val envSecrets =
            if (BackupCategory.ENVIRONMENT_VARIABLES in categories) {
                val repo = app?.let { if (it.subsystemsReady()) it.envVarRepository else null }
                repo?.entries?.value.orEmpty().mapNotNull { entry ->
                    val value = repo?.getValue(entry.key)
                    if (value.isNullOrEmpty()) null
                    else BackupSecrets.EnvVarSecret(
                        name = entry.key,
                        value = android.util.Base64.encodeToString(
                            value.toByteArray(), android.util.Base64.NO_WRAP
                        ),
                    )
                }
            } else emptyList()

        if (providerSecrets.isEmpty() && envSecrets.isEmpty()) return

        val secrets = BackupSecrets(providers = providerSecrets, envVars = envSecrets)
        File(staging, "secrets.json").writeText(BackupFormat.encodeSecrets(secrets))
        AppLogger.warning(
            TAG,
            "[Backup] package contains UNENCRYPTED credentials (stage 3a): " +
                "providers=${providerSecrets.size} envVars=${envSecrets.size}"
        )
    }


    private fun encryptStagedMembers(staging: File, keys: BackupCrypto.Keys) {
        val base = staging.canonicalFile
        val members = base.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .toList()
        for (file in members) {
            val rel = file.relativeTo(base).path.replace(File.separatorChar, '/')
            val key = if (rel == "secrets.json") keys.secretsKey else keys.dataKey
            val dest = File(file.parentFile, "${file.name}.enc")
            BackupCrypto.encryptFile(file, dest, key, "$rel.enc")
            file.delete()
        }
        AppLogger.info(TAG, "[Backup] encrypted ${members.size} member(s)")
    }

    private fun writeBlobIndex(entries: List<BackupBlobIndexEntry>, staging: File) {
        File(staging, "blobs.index.jsonl").outputStream().buffered().use { out ->
            for (e in entries) {
                val line = BackupFormat.json
                    .encodeToString(BackupBlobIndexEntry.serializer(), e) + "\n"
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun buildManifest(
        backupId: String,
        categories: Map<String, BackupManifest.CategoryStat>,
        blobStore: BackupBlobStore,
        staging: File,
        encryption: BackupManifest.Encryption?,
        options: Options,
        deviceName: String,
    ): BackupManifest {
        val base = staging.canonicalFile
        val integrity = base.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .associate { file ->
                file.relativeTo(base).path.replace(File.separatorChar, '/') to
                    BackupBlobStore.sha256OfFile(file)
            }

        return BackupManifest(
            createdAt = iso8601(System.currentTimeMillis()),
            snapshotAt = iso8601(options.snapshotAtMillis),
            app = BackupManifest.AppInfo(
                platform = "android",
                version = appVersion(),
                build = appBuild(),
            ),
            deviceName = deviceName,
            backupId = backupId,
            categories = categories,
            limits = BackupManifest.Limits(
                maxFileBytes = blobStore.maxFileBytesForManifest,
                skippedFiles = blobStore.skippedFiles,
                skippedBytes = blobStore.skippedBytes,
            ),
            encryption = encryption,
            integrity = integrity,
            manifestMac = null,
        )
    }

    private fun archive(
        staging: File,
        backupId: String,
        snapshotAtMillis: Long,
        deviceName: String,
        encrypted: Boolean,
    ): File {
        val name = BackupPackageName.packageFileName(backupId, snapshotAtMillis, deviceName, encrypted)
        val out = File(backupsDirectory(context), name)
        out.parentFile?.mkdirs()
        out.delete()

        val partial = File(out.parentFile, ".${out.name}.partial")
        partial.delete()
        try {
            BackupZip.archive(staging, partial)
            if (!partial.renameTo(out)) {
                throw BackupException("Could not finalise the backup package.")
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        return out
    }


    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun appBuild(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
    }.getOrDefault("?")

    companion object {
        private const val TAG = "Backup"

        private val activityLock = Mutex()

        fun backupsDirectory(context: Context): File =
            File(context.filesDir, BackupFileTreeExporter.BACKUPS_DIR_NAME).apply { mkdirs() }

        fun skillCount(skillsRoot: File): Int =
            skillsRoot.listFiles()?.count { it.isDirectory } ?: 0

        fun mcpServerCount(source: File): Int = runCatching {
            org.json.JSONObject(source.readText()).optJSONObject("mcpServers")?.length() ?: 1
        }.getOrDefault(1)

        internal fun messageRecord(m: MessageEntity): JsonElement = buildJsonObject {
            put("id", JsonPrimitive(m.id))
            put("sessionId", JsonPrimitive(m.sessionId))
            put("role", JsonPrimitive(m.role))
            put("parts", parseParts(m.partsJson))
            put("createdAt", JsonPrimitive(iso8601(m.createdAt)))
            put("tokenUsage", m.tokenUsage?.let { parseJsonOrNull(it) } ?: JsonNull)
            put("reasoningContent", m.reasoningContent?.let(::JsonPrimitive) ?: JsonNull)
            put("streamInterruptCount", JsonPrimitive(m.streamInterruptCount))
            put("sortOrder", JsonPrimitive(m.sortOrder))
            m.modelId?.let { put("modelId", JsonPrimitive(it)) }
            m.modelDisplayName?.let { put("modelDisplayName", JsonPrimitive(it)) }
            m.providerType?.let { put("providerType", JsonPrimitive(it)) }
            m.providerInstanceId?.let { put("providerInstanceId", JsonPrimitive(it)) }
        }

        internal fun markerRecord(c: CompactMarkerEntity): JsonElement = buildJsonObject {
            put("id", JsonPrimitive(c.id))
            put("sessionId", JsonPrimitive(c.sessionId))
            put("summary", JsonPrimitive(c.summary))
            put("firstKeptSortOrder", JsonPrimitive(c.firstKeptSortOrder))
            put("compactedCount", JsonPrimitive(c.compactedCount))
            put("createdAt", JsonPrimitive(iso8601(c.createdAt)))
            put("uiBoundarySortOrder", c.uiBoundarySortOrder?.let(::JsonPrimitive) ?: JsonNull)
            put("boundaryMessageId", c.boundaryMessageId?.let(::JsonPrimitive) ?: JsonNull)
            put("firstKeptMessageId", c.firstKeptMessageId?.let(::JsonPrimitive) ?: JsonNull)
            put("lastCompactedMessageId", c.lastCompactedMessageId?.let(::JsonPrimitive) ?: JsonNull)
            put("version", JsonPrimitive(c.version))
        }

        private fun parseParts(partsJson: String): JsonElement =
            parseJsonOrNull(partsJson) ?: BackupFormat.json.parseToJsonElement("[]")

        private fun parseJsonOrNull(raw: String): JsonElement? =
            runCatching { BackupFormat.json.parseToJsonElement(raw) }.getOrNull()

        internal fun sessionRecord(s: ChatSessionEntity): JsonElement = buildJsonObject {
            put("session", buildJsonObject {
                put("id", JsonPrimitive(s.id))
                put("title", s.title?.let(::JsonPrimitive) ?: JsonNull)
                put("category", s.category?.let(::JsonPrimitive) ?: JsonNull)
                put("modelId", JsonPrimitive(s.modelId))
                put("createdAt", JsonPrimitive(iso8601(s.createdAt)))
                put("updatedAt", JsonPrimitive(iso8601(s.updatedAt)))
                put("lastMessage", s.lastMessage?.let(::JsonPrimitive) ?: JsonNull)
                put("source", s.source?.let(::JsonPrimitive) ?: JsonNull)
                put("pinnedAt", s.pinnedAt?.let { JsonPrimitive(iso8601(it)) } ?: JsonNull)
                put("folderId", s.folderId?.let(::JsonPrimitive) ?: JsonNull)
                put("parentSessionId", s.parentSessionId?.let(::JsonPrimitive) ?: JsonNull)
                put("parentToolUseId", s.parentToolUseId?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("memoryEnabled", JsonPrimitive(s.memoryEnabled != 0))
            put("modelBinding", s.modelBinding?.let(::JsonPrimitive) ?: JsonNull)
            put("editCount", JsonPrimitive(s.editCount))
            put("thinkingOverride", s.thinkingOverride?.let(::JsonPrimitive) ?: JsonNull)
        }

        internal fun folderRecord(f: FolderEntity): JsonElement = buildJsonObject {
            put("id", JsonPrimitive(f.id))
            put("name", JsonPrimitive(f.name))
            put("icon", f.icon?.let(::JsonPrimitive) ?: JsonNull)
            put("color", f.color?.let(::JsonPrimitive) ?: JsonNull)
            put("origin", JsonPrimitive(f.origin))
            put("sortIndex", JsonPrimitive(f.sortIndex))
            put("pinnedAt", f.pinnedAt?.let { JsonPrimitive(iso8601(it)) } ?: JsonNull)
            put("desc", f.description?.let(::JsonPrimitive) ?: JsonNull)
            put("description", f.description?.let(::JsonPrimitive) ?: JsonNull)
            put("createdAt", JsonPrimitive(iso8601(f.createdAt)))
            put("updatedAt", JsonPrimitive(iso8601(f.updatedAt)))
        }

        fun iso8601(millis: Long): String = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)
    }
}
