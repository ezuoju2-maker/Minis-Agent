package com.openminis.app.speech.correction

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.util.UUID

class VoiceCorrectionDb private constructor(private val dbFile: File) {

    private var db: SQLiteDatabase? = null

    init {
        db = openOrRebuild(dbFile)
    }


    fun upsertConfusion(
        phoneticKey: String,
        originalVariant: String,
        correctedTerm: String,
        locale: String,
        contextSample: String?,
        asrProvider: String,
        source: String = SOURCE_VOICE,
    ) {
        val d = db ?: return
        val now = System.currentTimeMillis() / 1000.0
        runCatching {
            var existingId: String? = null
            var frequency = 0
            var negative = 0
            var variantsJson = "[]"
            d.rawQuery(
                """
                SELECT id, frequency, negative_feedback_count, original_variants
                FROM confusion_dictionary
                WHERE original_phonetic_key = ? AND corrected_term = ? AND locale = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(phoneticKey, correctedTerm, locale),
            ).use { c ->
                if (c.moveToFirst()) {
                    existingId = c.getString(0)
                    frequency = c.getInt(1)
                    negative = c.getInt(2)
                    variantsJson = c.getString(3) ?: "[]"
                }
            }

            val variants = decodeVariants(variantsJson).toMutableList()
            if (!variants.contains(originalVariant)) variants.add(originalVariant)
            val encoded = encodeVariants(variants)

            if (existingId != null) {
                val newFreq = frequency + 1
                val values = ContentValues().apply {
                    put("original_variants", encoded)
                    put("frequency", newFreq)
                    put("confidence", confidence(newFreq, negative))
                    put("last_seen", now)
                    if (contextSample != null) put("context_before_sample", contextSample)
                    else putNull("context_before_sample")
                    putNull("archived_at")
                }
                d.update("confusion_dictionary", values, "id = ?", arrayOf(existingId))
            } else {
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("original_phonetic_key", phoneticKey)
                    put("original_variants", encoded)
                    put("corrected_term", correctedTerm)
                    put("locale", locale)
                    put("frequency", 1)
                    put("negative_feedback_count", 0)
                    put("confidence", confidence(1, 0))
                    if (contextSample != null) put("context_before_sample", contextSample)
                    else putNull("context_before_sample")
                    put("asr_provider", asrProvider)
                    put("source", source)
                    put("last_seen", now)
                    putNull("archived_at")
                }
                d.insert("confusion_dictionary", null, values)
            }
            Log.i(
                TAG,
                "[Record] upsert originalLen=${originalVariant.length} " +
                    "correctedLen=${correctedTerm.length} locale=$locale source=$source",
            )
        }.onFailure { Log.e(TAG, "upsertConfusion failed", it) }
    }

    fun recordNegativeFeedback(phoneticKey: String, correctedTerm: String, locale: String) {
        val d = db ?: return
        runCatching {
            d.execSQL(
                """
                UPDATE confusion_dictionary
                SET negative_feedback_count = negative_feedback_count + 1,
                    confidence = CAST(frequency AS REAL) /
                                 (frequency + (negative_feedback_count + 1) * 2.0)
                WHERE original_phonetic_key = ? AND corrected_term = ? AND locale = ?
                """.trimIndent(),
                arrayOf(phoneticKey, correctedTerm, locale),
            )
        }.onFailure { Log.e(TAG, "recordNegativeFeedback failed", it) }
    }

    fun lookupConfusion(
        phoneticKeys: List<String>,
        locale: String,
        minConfidence: Double,
        limit: Int,
    ): List<ConfusionRow> {
        val d = db ?: return emptyList()
        if (phoneticKeys.isEmpty()) return emptyList()
        val placeholders = phoneticKeys.joinToString(",") { "?" }
        val args = (phoneticKeys + listOf(locale, minConfidence.toString(), limit.toString()))
            .toTypedArray()
        return runCatching {
            val out = mutableListOf<ConfusionRow>()
            d.rawQuery(
                """
                SELECT id, original_phonetic_key, original_variants, corrected_term,
                       locale, frequency, negative_feedback_count, confidence, last_seen
                FROM confusion_dictionary
                WHERE original_phonetic_key IN ($placeholders)
                  AND locale = ?
                  AND confidence >= ?
                  AND archived_at IS NULL
                ORDER BY confidence DESC, frequency DESC
                LIMIT ?
                """.trimIndent(),
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ConfusionRow(
                            id = c.getString(0),
                            phoneticKey = c.getString(1),
                            variants = decodeVariants(c.getString(2) ?: "[]"),
                            correctedTerm = c.getString(3),
                            locale = c.getString(4),
                            frequency = c.getInt(5),
                            negativeFeedbackCount = c.getInt(6),
                            confidence = c.getDouble(7),
                            lastSeen = c.getDouble(8),
                        ),
                    )
                }
            }
            out
        }.getOrElse {
            Log.e(TAG, "lookupConfusion failed", it)
            emptyList()
        }
    }


    fun upsertVocabulary(
        term: String,
        phoneticKey: String,
        locale: String,
        posTag: String?,
        backgroundRank: Int?,
        occurrences: Int,
        day: String,
    ) {
        val d = db ?: return
        val now = System.currentTimeMillis() / 1000.0
        runCatching {
            var id: String? = null
            var freq = 0
            var days = 0
            var lastDay: String? = null
            d.rawQuery(
                "SELECT id, frequency, distinct_days, last_day FROM typed_vocabulary " +
                    "WHERE term = ? AND locale = ? LIMIT 1",
                arrayOf(term, locale),
            ).use { c ->
                if (c.moveToFirst()) {
                    id = c.getString(0); freq = c.getInt(1)
                    days = c.getInt(2); lastDay = c.getString(3)
                }
            }
            if (id != null) {
                val values = ContentValues().apply {
                    put("frequency", freq + occurrences)
                    put("distinct_days", if (lastDay != day) days + 1 else days)
                    put("last_day", day)
                    put("last_seen", now)
                    if (posTag != null) put("pos_tag", posTag)
                    if (backgroundRank != null) put("background_rank", backgroundRank)
                    putNull("archived_at")
                }
                d.update("typed_vocabulary", values, "id = ?", arrayOf(id))
            } else {
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("term", term)
                    put("phonetic_key", phoneticKey)
                    put("locale", locale)
                    if (posTag != null) put("pos_tag", posTag) else putNull("pos_tag")
                    put("frequency", occurrences)
                    put("distinct_days", 1)
                    put("last_day", day)
                    if (backgroundRank != null) put("background_rank", backgroundRank)
                    else putNull("background_rank")
                    put("last_seen", now)
                    put("source", "typed")
                    putNull("archived_at")
                }
                d.insert("typed_vocabulary", null, values)
            }
        }.onFailure { Log.e(TAG, "upsertVocabulary failed", it) }
    }

    fun lookupVocabulary(
        phoneticKeys: List<String>,
        locale: String,
        limit: Int,
        minFrequency: Int = VoiceCorrectionConfig.VOCAB_MIN_FREQUENCY,
        minDistinctDays: Int = VoiceCorrectionConfig.VOCAB_MIN_DISTINCT_DAYS,
    ): List<VocabularyRow> {
        val d = db ?: return emptyList()
        if (phoneticKeys.isEmpty()) return emptyList()
        val placeholders = phoneticKeys.joinToString(",") { "?" }
        val args = (
            phoneticKeys + listOf(
                locale, minFrequency.toString(), minDistinctDays.toString(), limit.toString(),
            )
            ).toTypedArray()
        return runCatching {
            val out = mutableListOf<VocabularyRow>()
            d.rawQuery(
                """
                SELECT id, term, phonetic_key, locale, pos_tag, frequency, distinct_days
                FROM typed_vocabulary
                WHERE phonetic_key IN ($placeholders)
                  AND locale = ?
                  AND frequency >= ?
                  AND distinct_days >= ?
                  AND archived_at IS NULL
                ORDER BY frequency DESC
                LIMIT ?
                """.trimIndent(),
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        VocabularyRow(
                            id = c.getString(0), term = c.getString(1),
                            phoneticKey = c.getString(2), locale = c.getString(3),
                            posTag = c.getString(4), frequency = c.getInt(5),
                            distinctDays = c.getInt(6),
                        ),
                    )
                }
            }
            out
        }.getOrElse {
            Log.e(TAG, "lookupVocabulary failed", it)
            emptyList()
        }
    }


    fun metaValue(key: String): String? {
        val d = db ?: return null
        return runCatching {
            d.rawQuery("SELECT value FROM correction_meta WHERE key = ? LIMIT 1", arrayOf(key))
                .use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    fun setMetaValue(key: String, value: String) {
        val d = db ?: return
        runCatching {
            d.execSQL(
                "INSERT INTO correction_meta(key, value) VALUES(?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(key, value),
            )
        }.onFailure { Log.e(TAG, "setMetaValue failed", it) }
    }

    fun recordCorrectionEvent(
        original: String,
        corrected: String,
        outcome: String,
        sourceSummary: String?,
    ) {
        val d = db ?: return
        runCatching {
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("created_at", System.currentTimeMillis() / 1000.0)
                put("original", original)
                put("corrected", corrected)
                put("outcome", outcome)
                if (sourceSummary != null) put("source_summary", sourceSummary)
                else putNull("source_summary")
            }
            d.insert("correction_events", null, values)
        }.onFailure { Log.e(TAG, "recordCorrectionEvent failed", it) }
    }

    fun counts(): Map<String, Int> {
        val d = db ?: return emptyMap()
        return runCatching {
            listOf("confusion_dictionary", "typed_vocabulary", "correction_events")
                .associateWith { t ->
                    d.rawQuery("SELECT COUNT(*) FROM $t", null)
                        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                }
        }.getOrElse { emptyMap() }
    }

    fun clearAll() {
        val d = db ?: return
        runCatching {
            d.beginTransaction()
            try {
                d.execSQL("DELETE FROM confusion_dictionary")
                d.execSQL("DELETE FROM typed_vocabulary")
                d.execSQL("DELETE FROM correction_events")
                d.execSQL("DELETE FROM correction_meta")
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            d.rawQuery("PRAGMA incremental_vacuum(1000)", null).use { it.moveToFirst() }
            Log.i(TAG, "[Privacy] all correction learning data cleared")
        }.onFailure { Log.e(TAG, "clearAll failed", it) }
    }


    private fun openOrRebuild(file: File): SQLiteDatabase? {
        openAndPrepare(file)?.let { return it }
        Log.e(TAG, "[DBHealth] open failed — rebuilding empty (size=${file.length()})")
        runCatching {
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
        return openAndPrepare(file).also {
            if (it == null) Log.e(TAG, "[DBHealth] rebuild failed — correction disabled")
        }
    }

    private fun openAndPrepare(file: File): SQLiteDatabase? = runCatching<SQLiteDatabase> {
        file.parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(file, null)
        d.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }
        d.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
        d.execSQL("PRAGMA synchronous = NORMAL")
        runMigrations(d)
        d
    }.onFailure { Log.e(TAG, "[DBHealth] openAndPrepare failed", it) }.getOrNull()

    private fun runMigrations(d: SQLiteDatabase) {
        val current = d.rawQuery("PRAGMA user_version", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val patched = ensureIdempotentColumns(d)

        if (current >= SCHEMA_VERSION) {
            if (patched > 0) Log.i(TAG, "[Migration] at v$current, columns patched=$patched")
            return
        }

        d.beginTransaction()
        try {
            if (current < 1) {
                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS confusion_dictionary (
                        id                      TEXT PRIMARY KEY,
                        original_phonetic_key   TEXT NOT NULL,
                        original_variants       TEXT NOT NULL,
                        corrected_term          TEXT NOT NULL,
                        locale                  TEXT NOT NULL,
                        frequency               INTEGER NOT NULL DEFAULT 1,
                        negative_feedback_count INTEGER NOT NULL DEFAULT 0,
                        confidence              REAL NOT NULL DEFAULT 0.5,
                        context_before_sample   TEXT,
                        asr_provider            TEXT NOT NULL DEFAULT '',
                        source                  TEXT NOT NULL DEFAULT '$SOURCE_VOICE',
                        last_seen               REAL NOT NULL,
                        archived_at             REAL DEFAULT NULL,
                        device_id               TEXT,
                        last_synced_at          REAL
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_phonetic ON confusion_dictionary(original_phonetic_key)")
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_locale ON confusion_dictionary(locale)")
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_confidence ON confusion_dictionary(confidence DESC)")

                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS typed_vocabulary (
                        id              TEXT PRIMARY KEY,
                        term            TEXT NOT NULL,
                        phonetic_key    TEXT NOT NULL,
                        locale          TEXT NOT NULL,
                        pos_tag         TEXT,
                        frequency       INTEGER NOT NULL DEFAULT 1,
                        distinct_days   INTEGER NOT NULL DEFAULT 1,
                        last_day        TEXT,
                        background_rank INTEGER,
                        last_seen       REAL NOT NULL,
                        source          TEXT NOT NULL DEFAULT 'typed',
                        archived_at     REAL DEFAULT NULL
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_vocab_phonetic ON typed_vocabulary(phonetic_key)")

                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS correction_meta (" +
                        "key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                )
                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS correction_events (
                        id             TEXT PRIMARY KEY,
                        created_at     REAL NOT NULL,
                        original       TEXT NOT NULL,
                        corrected      TEXT NOT NULL,
                        outcome        TEXT NOT NULL,
                        source_summary TEXT
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created ON correction_events(created_at DESC)")
            }
            d.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        Log.i(TAG, "[Migration] schema $current → $SCHEMA_VERSION")
    }

    private fun ensureIdempotentColumns(d: SQLiteDatabase): Int {
        var added = 0
        fun columns(table: String): Set<String> = runCatching {
            d.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val s = mutableSetOf<String>()
                while (c.moveToNext()) s.add(c.getString(1))
                s
            }
        }.getOrElse { emptySet() }

        fun ensure(table: String, column: String, ddl: String) {
            val existing = columns(table)
            if (existing.isEmpty() || existing.contains(column)) return
            runCatching {
                d.execSQL("ALTER TABLE $table ADD COLUMN $ddl")
                added++
                Log.i(TAG, "[Migration] schema repair: added $table.$column")
            }.onFailure { Log.e(TAG, "column repair failed for $table.$column", it) }
        }
        ensure("confusion_dictionary", "archived_at", "archived_at REAL DEFAULT NULL")
        ensure("confusion_dictionary", "source", "source TEXT NOT NULL DEFAULT '$SOURCE_VOICE'")
        ensure("typed_vocabulary", "archived_at", "archived_at REAL DEFAULT NULL")
        ensure("typed_vocabulary", "last_day", "last_day TEXT")
        return added
    }

    companion object {
        private const val TAG = "VoiceCorrectionDB"

        const val SCHEMA_VERSION = 1

        const val SOURCE_VOICE = "voice"

        const val SOURCE_TEXT = "text_input"

        @Volatile
        private var instance: VoiceCorrectionDb? = null

        fun getInstance(context: Context): VoiceCorrectionDb? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: runCatching {
                    val file = File(context.applicationContext.filesDir, "voice-correction.db")
                    VoiceCorrectionDb(file).takeIf { it.db != null }
                }.getOrNull()?.also { instance = it }
            }
        }

        fun confidence(frequency: Int, negative: Int): Double {
            val f = maxOf(frequency, 0).toDouble()
            val n = maxOf(negative, 0).toDouble()
            val denominator = f + n * 2.0
            if (denominator <= 0) return 0.5
            return f / denominator
        }

        fun dayKey(epochSeconds: Double): String {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = (epochSeconds * 1000).toLong()
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }

        internal fun decodeVariants(json: String): List<String> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrElse { emptyList() }

        internal fun encodeVariants(variants: List<String>): String =
            JSONArray().apply { variants.forEach { put(it) } }.toString()
    }
}

data class ConfusionRow(
    val id: String,
    val phoneticKey: String,
    val variants: List<String>,
    val correctedTerm: String,
    val locale: String,
    val frequency: Int,
    val negativeFeedbackCount: Int,
    val confidence: Double,
    val lastSeen: Double,
)

data class VocabularyRow(
    val id: String,
    val term: String,
    val phoneticKey: String,
    val locale: String,
    val posTag: String?,
    val frequency: Int,
    val distinctDays: Int,
)
