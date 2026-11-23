package com.openminis.app.speech.correction

import android.content.Context
import android.util.Log
import com.openminis.app.data.db.ChatDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

class TypedVocabularyBuilder(
    private val context: Context,
    private val db: VoiceCorrectionDb?,
    private val dao: ChatDao,
    private val posTag: (String) -> List<Pair<String, String>>,
    private val segment: (String) -> List<String>,
    private val rank: (String) -> Int?,
) {

    private val buildLock = Mutex()

    suspend fun buildIfNeeded(force: Boolean = false) {
        val database = db ?: return
        if (!force) {
            val last = database.metaValue(KEY_LAST_BUILD_TIME)?.toDoubleOrNull() ?: 0.0
            val nowSec = System.currentTimeMillis() / 1000.0
            if (nowSec - last < MIN_INTERVAL_SECONDS) return
        }
        build(force)
    }

    suspend fun build(force: Boolean = false) {
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        val database = db ?: return
        if (!buildLock.tryLock()) return

        try {
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val cursorMs = if (force) 0L
                else database.metaValue(KEY_CURSOR)?.toLongOrNull() ?: 0L

                val messages = runCatching {
                    dao.loadUserMessagesSince(cursorMs, MAX_MESSAGES_PER_BUILD)
                }.getOrElse {
                    Log.e(TAG, "failed to load messages", it)
                    return@withContext
                }
                if (messages.isEmpty()) return@withContext

                var newest = cursorMs
                var candidates = 0
                var accepted = 0
                var rejected = 0

                for (m in messages) {
                    newest = maxOf(newest, m.createdAt)
                    val text = stripAttachmentMarkup(extractText(m.partsJson))
                    if (text.isBlank()) continue
                    val day = VoiceCorrectionDb.dayKey(m.createdAt / 1000.0)

                    for ((term, occurrences, tag) in
                    VocabularyFilter.candidates(text, posTag, segment)) {
                        candidates++
                        when (val d = VocabularyFilter.evaluate(term, tag, rank)) {
                            is VocabularyFilter.Decision.Accepted -> {
                                val key = PinyinNormalizer.normalize(term)
                                if (key.isEmpty()) { rejected++; continue }
                                database.upsertVocabulary(
                                    term = term,
                                    phoneticKey = key,
                                    locale = "zh",
                                    posTag = tag,
                                    backgroundRank = rank(term),
                                    occurrences = occurrences,
                                    day = day,
                                )
                                accepted++
                            }
                            is VocabularyFilter.Decision.Rejected -> {
                                rejected++
                                if (d.reason.isNotEmpty()) Unit
                            }
                        }
                    }
                }

                database.setMetaValue(KEY_CURSOR, newest.toString())
                database.setMetaValue(
                    KEY_LAST_BUILD_TIME,
                    (System.currentTimeMillis() / 1000.0).toString(),
                )
                Log.i(
                    TAG,
                    "[Vocab] messages=${messages.size} candidates=$candidates " +
                        "accepted=$accepted rejected=$rejected " +
                        "durationMs=${System.currentTimeMillis() - started}",
                )
            }
        } finally {
            buildLock.unlock()
        }
    }

    internal fun extractText(partsJson: String): String = runCatching {
        val arr = JSONArray(partsJson)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") != "text") continue
            val t = obj.optString("text")
            if (t.isNotEmpty()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) }
        }
        sb.toString()
    }.getOrElse { "" }

    companion object {
        private const val TAG = "TypedVocabulary"

        private const val KEY_CURSOR = "vocab_last_built_at"

        private const val KEY_LAST_BUILD_TIME = "vocab_last_build_time"

        private const val MIN_INTERVAL_SECONDS = 3600.0

        private const val MAX_MESSAGES_PER_BUILD = 500

        internal fun stripAttachmentMarkup(text: String): String {
            val start = text.indexOf("<user-attached-files>")
            if (start < 0) return text.trim()
            val endTag = "</user-attached-files>"
            val end = text.indexOf(endTag, start)
            if (end < 0) return text.trim()
            return (text.substring(0, start) + text.substring(end + endTag.length)).trim()
        }
    }
}
