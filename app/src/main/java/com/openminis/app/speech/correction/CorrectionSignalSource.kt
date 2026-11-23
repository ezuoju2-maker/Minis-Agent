package com.openminis.app.speech.correction

interface CorrectionSignalSource {
    val sourceIdentifier: String

    val defaultWeight: Double

    fun lookupCandidates(phoneticKeys: List<String>, locale: String, limit: Int): List<CorrectionCandidate>
}

data class CorrectionCandidate(
    val phoneticKey: String,
    val term: String,
    val confidence: Double,
    val sourceIdentifier: String,
    val evidence: String,
)

class ConfusionDictionarySource(
    private val db: VoiceCorrectionDb,
    private val minConfidence: Double = VoiceCorrectionConfig.MIN_CONFUSION_CONFIDENCE,
) : CorrectionSignalSource {

    override val sourceIdentifier = "confusion_dictionary"
    override val defaultWeight = 1.0

    override fun lookupCandidates(
        phoneticKeys: List<String>,
        locale: String,
        limit: Int,
    ): List<CorrectionCandidate> =
        db.lookupConfusion(phoneticKeys, locale, minConfidence, limit).map { row ->
            CorrectionCandidate(
                phoneticKey = row.phoneticKey,
                term = row.correctedTerm,
                confidence = row.confidence,
                sourceIdentifier = sourceIdentifier,
                evidence = "${row.variants.firstOrNull() ?: row.phoneticKey}→${row.correctedTerm}" +
                    "（出现${row.frequency}次）",
            )
        }
}

class TypedVocabularySource(
    private val db: VoiceCorrectionDb,
) : CorrectionSignalSource {

    override val sourceIdentifier = "typed_vocabulary"
    override val defaultWeight = 0.6

    override fun lookupCandidates(
        phoneticKeys: List<String>,
        locale: String,
        limit: Int,
    ): List<CorrectionCandidate> =
        db.lookupVocabulary(phoneticKeys, locale, limit).map { row ->
            CorrectionCandidate(
                phoneticKey = row.phoneticKey,
                term = row.term,
                confidence = minOf(1.0, row.frequency / 10.0),
                sourceIdentifier = sourceIdentifier,
                evidence = "${row.term}（常用词，出现${row.frequency}次）",
            )
        }
}
