package com.openminis.app.backup

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BackupHistory internal constructor(
    private val dir: File,
    private val writer: Executor = WRITER,
    private val sanitize: (List<LogEntry>) -> List<LogEntry> = BackupLogRules::sanitize,
) {

    @Serializable
    enum class Status { RUNNING, SUCCEEDED, COMPLETED_WITH_ISSUES, FAILED }

    @Serializable
    data class LogEntry(
        val at: Long,
        val message: String,
        val isProblem: Boolean = false,
        val isTransient: Boolean = false,
    )

    @Serializable
    data class DestinationOutcome(
        val name: String,
        val succeeded: Boolean,
        val detail: String? = null,
        val kind: String? = null,
        val path: String? = null,
    )

    @Serializable
    data class SkippedEntry(
        val path: String,
        val size: Long,
        val reason: String? = null,
    ) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    @Serializable
    data class Record(
        val id: String = UUID.randomUUID().toString(),
        val backupId: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val status: Status,
        val categories: List<String> = emptyList(),
        val encrypted: Boolean = false,
        val totalBytes: Long = 0,
        val skippedFiles: Int = 0,
        val skippedEntries: List<SkippedEntry> = emptyList(),
        val packageName: String? = null,
        val destinations: List<DestinationOutcome> = emptyList(),
        val log: List<LogEntry> = emptyList(),
        val errorMessage: String? = null,
    ) {
        val durationMillis: Long? get() = finishedAt?.let { it - startedAt }
        val hasProblems: Boolean
            get() = destinations.any { !it.succeeded } || skippedFiles > 0
    }


    private val storeFile: File
        get() = File(dir.apply { mkdirs() }, STORE_NAME)

    @Volatile
    private var cache: List<Record>? = null

    private var version = 0L
    private val writeLock = Any()
    private var writtenVersion = 0L
    private val writeQueued = AtomicBoolean(false)

    @Volatile
    internal var writeHook: (() -> Unit)? = null

    @Synchronized
    fun records(): List<Record> {
        cache?.let { return it }
        val (raw, loaded) = load()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = loaded.filter { it.startedAt >= cutoff }.sortedByDescending { it.startedAt }
        val cleaned = clean(kept)
        if (cleaned !== kept && raw != null) keepBackup(raw)
        if (cleaned !== kept || kept.size != loaded.size) persist(cleaned)
        cache = cleaned
        return cleaned
    }

    private fun clean(records: List<Record>): List<Record> = runCatching {
        var changed = false
        val out = records.map { r ->
            val log = sanitize(r.log)
            if (log == r.log) r else { changed = true; r.copy(log = log) }
        }
        if (changed) out else records
    }.getOrElse {
        AppLogger.error(TAG, "[Backup] history log cleanup failed, kept as is: ${it.message}")
        records
    }

    private fun keepBackup(raw: String) {
        runCatching {
            writer.execute {
                runCatching {
                    val tmp = File(dir, "$STORE_NAME.bak.tmp")
                    tmp.writeText(raw)
                    Files.move(
                        tmp.toPath(), File(dir, "$STORE_NAME.bak").toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                    AppLogger.info(TAG, "[Backup] history log cleaned; original kept as $STORE_NAME.bak")
                }.onFailure { AppLogger.error(TAG, "[Backup] history .bak write failed: ${it.message}") }
            }
        }.onFailure { AppLogger.error(TAG, "[Backup] history .bak not scheduled: ${it.message}") }
    }

    private fun load(): Pair<String?, List<Record>> {
        val f = storeFile
        if (!f.exists()) return null to emptyList()
        val text = runCatching { f.readText() }.getOrElse {
            AppLogger.error(TAG, "[Backup] history read failed: ${it.message}")
            return null to emptyList()
        }
        return text to runCatching { JSON.decodeFromString(RECORD_LIST, text) }.getOrElse { e ->
            val aside = File(dir, "$STORE_NAME.corrupt-${System.currentTimeMillis()}")
            val moved = f.renameTo(aside)
            AppLogger.error(
                TAG,
                "[Backup] history file does not decode (${e.javaClass.simpleName}, ${text.length} chars); " +
                    "kept as ${aside.name} (moved=$moved), starting empty",
            )
            emptyList()
        }
    }

    @Synchronized
    private fun persist(list: List<Record>) {
        cache = list
        version++
        if (writeQueued.compareAndSet(false, true)) {
            runCatching { writer.execute { writeLatest() } }.onFailure {
                writeQueued.set(false)
                AppLogger.error(TAG, "[Backup] history write not scheduled: ${it.message}")
            }
        }
    }

    private fun writeLatest() = synchronized(writeLock) {
        writeQueued.set(false)
        val (snapshot, v) = synchronized(this) { cache to version }
        if (snapshot == null || v <= writtenVersion) return@synchronized
        runCatching {
            writeHook?.invoke()
            val bytes = JSON.encodeToString(RECORD_LIST, snapshot).toByteArray(Charsets.UTF_8)
            val tmp = File(dir.apply { mkdirs() }, "$STORE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(
                tmp.toPath(), storeFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            writtenVersion = v
        }.onFailure { AppLogger.error(TAG, "[Backup] history write failed: ${it.message}") }
    }

    internal fun flushWrites() = writeLatest()


    @Synchronized
    fun upsert(record: Record) {
        val cur = records().toMutableList()
        val i = cur.indexOfFirst { it.id == record.id }
        if (i >= 0) cur[i] = record else cur.add(0, record)
        persist(cur.sortedByDescending { it.startedAt })
    }

    @Synchronized
    fun remove(id: String) {
        persist(records().filterNot { it.id == id })
    }

    @Synchronized
    fun renameDestination(from: String, to: String) {
        val cur = records()
        if (cur.none { r -> r.destinations.any { it.name == from } }) return
        persist(
            cur.map { r ->
                if (r.destinations.none { it.name == from }) r
                else r.copy(destinations = r.destinations.map { if (it.name == from) it.copy(name = to) else it })
            },
        )
    }

    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    @Synchronized
    private fun reconcileInterrupted() {
        val cur = records()
        if (cur.none { it.status == Status.RUNNING }) return
        persist(
            cur.map {
                if (it.status != Status.RUNNING) it
                else it.copy(
                    status = Status.FAILED,
                    finishedAt = it.finishedAt ?: System.currentTimeMillis(),
                    errorMessage = it.errorMessage ?: INTERRUPTED_MARKER,
                )
            },
        )
    }

    companion object {
        private const val TAG = "Backup"

        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

        const val INTERRUPTED_MARKER = "interrupted"

        private const val STORE_NAME = "records.json"

        private val WRITER: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "BackupHistoryWriter").apply { isDaemon = true }
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val RECORD_LIST = ListSerializer(Record.serializer())

        @Volatile
        private var instance: BackupHistory? = null

        fun get(context: Context): BackupHistory =
            instance ?: synchronized(this) {
                instance ?: BackupHistory(File(context.applicationContext.filesDir, "backup-history"))
                    .also { it.reconcileInterrupted(); instance = it }
            }
    }
}
