package com.openminis.app.speech.correction

import android.content.Context
import android.util.Log
import com.openminis.app.shared.SentenceSplitter

class VoiceCorrectionRecorder(
    private val context: Context,
    private val db: VoiceCorrectionDb?,
    private val segment: (String) -> List<String>,
) {

    fun recordTranscriptEdit(
        before: String,
        after: String,
        locale: String = "zh",
        asrProvider: String = "",
    ) = recordEdit(before, after, locale, asrProvider, VoiceCorrectionDb.SOURCE_VOICE)

    fun recordTextInputEdit(
        before: String,
        after: String,
        locale: String = "zh",
    ) = recordEdit(before, after, locale, "", VoiceCorrectionDb.SOURCE_TEXT)

    private fun recordEdit(
        before: String,
        after: String,
        locale: String,
        asrProvider: String,
        source: String,
    ) {
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        val b = before.trim()
        val a = after.trim()
        if (b.isEmpty() || a.isEmpty() || b == a) return

        val normalizer = PhoneticNormalizerRegistry.normalizer(locale) ?: return
        val database = db ?: return

        var accepted = 0
        var droppedRewrite = 0
        val admitReasons = mutableListOf<String>()
        val rejectReasons = mutableListOf<String>()

        for ((fromText, toText, contextSample) in alignedCandidates(b, a)) {
            val fromKey = normalizer.normalize(fromText)
            if (fromKey.isEmpty()) continue

            val sentenceLength = contextSample?.length ?: maxOf(b.length, a.length)
            val verdict = CorrectionAdmission.judge(
                from = fromText,
                to = toText,
                normalizer = normalizer,
                sentenceLength = sentenceLength,
            )
            if (!verdict.isAdmitted) {
                droppedRewrite++
                rejectReasons.add(verdict.reason)
                continue
            }
            admitReasons.add(verdict.reason)
            database.upsertConfusion(
                phoneticKey = fromKey,
                originalVariant = fromText,
                correctedTerm = toText,
                locale = locale,
                contextSample = contextSample?.take(VoiceCorrectionConfig.CONTEXT_SAMPLE_CHARS),
                asrProvider = asrProvider,
                source = source,
            )
            accepted++
        }
        Log.i(
            TAG,
            "[Record] source=$source beforeLen=${b.length} afterLen=${a.length} " +
                "accepted=$accepted droppedRewrite=$droppedRewrite " +
                "admit=$admitReasons reject=$rejectReasons",
        )
    }

    private fun alignedCandidates(
        before: String,
        after: String,
    ): List<Triple<String, String, String?>> {
        val beforeSentences = SentenceSplitter.split(before)
        val afterSentences = SentenceSplitter.split(after)

        val out = mutableListOf<Triple<String, String, String?>>()
        if (beforeSentences.size == afterSentences.size && beforeSentences.isNotEmpty()) {
            for (i in beforeSentences.indices) {
                val bs = beforeSentences[i]
                val `as` = afterSentences[i]
                if (bs == `as`) continue
                for (p in VoiceCorrectionDiff.tokenPairs(bs, `as`, segment)) {
                    out.add(Triple(p.from, p.to, bs))
                }
            }
        } else {
            for (p in VoiceCorrectionDiff.tokenPairs(before, after, segment)) {
                out.add(Triple(p.from, p.to, before))
            }
        }
        return out
    }

    fun recordSuggestionAccepted(original: String, corrected: String, summary: String) {
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        db?.recordCorrectionEvent(original, corrected, "accepted", summary.ifEmpty { null })
    }

    fun recordSuggestionRejected(
        pairs: List<VoiceCorrectionDiff.Pair>,
        locale: String = "zh",
    ) {
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        val database = db ?: return
        val normalizer = PhoneticNormalizerRegistry.normalizer(locale) ?: return
        for (p in pairs) {
            val key = normalizer.normalize(p.from)
            if (key.isEmpty()) continue
            database.recordNegativeFeedback(key, p.to, locale)
        }
        database.recordCorrectionEvent(
            pairs.joinToString("，") { it.from },
            pairs.joinToString("，") { it.to },
            "reverted",
            null,
        )
    }

    companion object {
        private const val TAG = "VoiceCorrectionRec"
    }
}
