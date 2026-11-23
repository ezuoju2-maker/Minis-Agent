package com.openminis.app.speech.correction

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CorrectionSuggestion(
    val original: String,
    val corrected: String,
    val hasChange: Boolean,
    val modelGroupUsed: String,
    val durationMs: Int,
    val rejectedReason: String? = null,
    val diffSummary: String = "",
    val appliedPairs: List<VoiceCorrectionDiff.Pair> = emptyList(),
    val failureDetail: String? = null,
)

class VoiceCorrectionEngine(
    private val sources: List<CorrectionSignalSource>,
    private val strategy: CorrectionStrategy,
    private val db: VoiceCorrectionDb?,
    private val segment: (String) -> List<String>,
    private val consent: () -> Boolean,
) {

    private data class CacheEntry(val candidates: List<CorrectionCandidate>, val at: Long)

    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    suspend fun correct(
        transcript: String,
        locale: String = "zh",
        context: ConversationContext = ConversationContext.EMPTY,
        persistEvent: Boolean = true,
    ): CorrectionSuggestion {
        val started = System.currentTimeMillis()
        val text = transcript.trim()
        if (text.isEmpty()) {
            return CorrectionSuggestion(transcript, transcript, false, "none", 0, "empty_input")
        }

        val candidates = retrieveCandidates(text, locale)

        val outcome = try {
            withContext(Dispatchers.IO) {
                strategy.correct(text, candidates, context, locale)
            }
        } catch (t: Throwable) {
            val last = (t as? CorrectionError.AllModelsFailed)?.attempts?.lastOrNull()?.error ?: t
            Log.w(
                TAG,
                "[VoiceCorrection][Correct] FAILED reason=llm_error:${last.javaClass.simpleName} " +
                    "attempts=${(t as? CorrectionError.AllModelsFailed)?.attempts?.size ?: 0} fallback=original_text " +
                    "durationMs=${System.currentTimeMillis() - started}",
                t,
            )
            return CorrectionSuggestion(
                transcript, transcript, false, "none",
                (System.currentTimeMillis() - started).toInt(),
                "llm_error:${last.javaClass.simpleName}",
                failureDetail = last.message,
            )
        }

        val corrected = outcome.correctedText
        if (corrected == text) {
            return CorrectionSuggestion(
                transcript, transcript, false, outcome.modelGroupUsed, outcome.durationMs,
            )
        }

        val ratio = VoiceCorrectionDiff.charChangeRatio(text, corrected)
        if (ratio > VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO) {
            Log.i(TAG, "[Correct] rejected diff_too_large ratio=${"%.2f".format(ratio)}")
            return CorrectionSuggestion(
                transcript, transcript, false, outcome.modelGroupUsed, outcome.durationMs,
                "diff_too_large",
            )
        }

        val pairs = VoiceCorrectionDiff.tokenPairs(text, corrected, segment)
        val summary = pairs.joinToString("，") { "${it.from}→${it.to}" }

        if (persistEvent && consent()) {
            db?.recordCorrectionEvent(text, corrected, "suggested", summary.ifEmpty { null })
        }

        return CorrectionSuggestion(
            original = transcript,
            corrected = corrected,
            hasChange = true,
            modelGroupUsed = outcome.modelGroupUsed,
            durationMs = (System.currentTimeMillis() - started).toInt(),
            diffSummary = summary,
            appliedPairs = pairs,
        )
    }

    suspend fun retrieveCandidates(transcript: String, locale: String): List<CorrectionCandidate> {
        val normalizer = PhoneticNormalizerRegistry.normalizer(locale) ?: return emptyList()
        val started = System.currentTimeMillis()

        val keys = runCatching { segment(transcript) }.getOrElse { emptyList() }
            .filter { it.length >= 2 }
            .map { normalizer.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (keys.isEmpty()) return emptyList()

        val (cached, missing) = partitionByCache(keys, locale)
        val fetched = if (missing.isEmpty()) emptyList() else coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    runCatching {
                        source.lookupCandidates(missing, locale, VoiceCorrectionConfig.MAX_CANDIDATES)
                    }.getOrElse { emptyList() }
                }
            }.flatMap { it.await() }
        }
        if (missing.isNotEmpty()) storeInCache(missing, fetched, locale)

        val elapsed = System.currentTimeMillis() - started
        if (elapsed > VoiceCorrectionConfig.RETRIEVAL_BUDGET_MS) {
            Log.i(TAG, "[Retrieve] OVER BUDGET ${elapsed}ms keys=${keys.size}")
        }
        return fuse(cached + fetched)
    }

    private fun fuse(candidates: List<CorrectionCandidate>): List<CorrectionCandidate> {
        val weights = sources.associate { it.sourceIdentifier to it.defaultWeight }
        fun score(c: CorrectionCandidate) = (weights[c.sourceIdentifier] ?: 0.5) * c.confidence

        val best = HashMap<String, CorrectionCandidate>()
        for (c in candidates) {
            val k = "${c.phoneticKey}|${c.term}"
            val existing = best[k]
            if (existing != null && score(existing) >= score(c)) continue
            best[k] = c
        }
        return best.values.sortedByDescending { score(it) }
            .take(VoiceCorrectionConfig.MAX_CANDIDATES)
    }


    private suspend fun partitionByCache(
        keys: List<String>,
        locale: String,
    ): Pair<List<CorrectionCandidate>, List<String>> = cacheLock.withLock {
        val now = System.currentTimeMillis()
        val hits = mutableListOf<CorrectionCandidate>()
        val missing = mutableListOf<String>()
        for (key in keys) {
            val entry = cache["$locale|$key"]
            if (entry != null && now - entry.at < VoiceCorrectionConfig.CACHE_TTL_MS) {
                hits.addAll(entry.candidates)
            } else {
                missing.add(key)
            }
        }
        hits to missing
    }

    private suspend fun storeInCache(
        keys: List<String>,
        candidates: List<CorrectionCandidate>,
        locale: String,
    ) = cacheLock.withLock {
        val byKey = candidates.groupBy { it.phoneticKey }
        val now = System.currentTimeMillis()
        for (key in keys) cache["$locale|$key"] = CacheEntry(byKey[key] ?: emptyList(), now)

        if (cache.size > VoiceCorrectionConfig.CACHE_LIMIT) {
            val excess = cache.size - VoiceCorrectionConfig.CACHE_LIMIT
            cache.entries.sortedBy { it.value.at }.take(excess).forEach { cache.remove(it.key) }
        }
    }

    suspend fun clearCache() = cacheLock.withLock { cache.clear() }

    internal fun fuseForTest(candidates: List<CorrectionCandidate>) = fuse(candidates)

    fun warmUp() {
        runCatching {
            segment("预热分词词典")
            PinyinNormalizer.normalize("预热")
        }.onFailure { Log.w(TAG, "warmUp failed", it) }
    }

    suspend fun dryRun(
        transcript: String,
        locale: String = "zh",
        context: ConversationContext = ConversationContext.EMPTY,
    ): Triple<List<String>, List<CorrectionCandidate>, String> {
        val tokens = runCatching { segment(transcript) }.getOrElse { emptyList() }
        val candidates = retrieveCandidates(transcript, locale)
        val prompt = LlmCorrectionStrategy.buildPrompt(
            transcript, candidates, context, CorrectionLanguageProfile.resolve(locale, transcript),
        )
        return Triple(tokens, candidates, prompt)
    }

    companion object {
        private const val TAG = "VoiceCorrectionEngine"
    }
}
