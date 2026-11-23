package com.openminis.app.speech.correction

import android.content.Context
import android.util.Log
import com.openminis.app.R

object VocabularyFilter {

    data class ScoreBreakdown(
        var backgroundRank: Int = 0,
        var posTag: Int = 0,
        var hasLatinOrDigit: Int = 0,
        var lengthOk: Int = 0,
    ) {
        val total: Int get() = backgroundRank + posTag + hasLatinOrDigit + lengthOk
    }

    sealed interface Decision {
        data class Accepted(val score: Int, val breakdown: ScoreBreakdown) : Decision
        data class Rejected(val reason: String) : Decision
    }

    private val PROPER_NOUN_TAGS = setOf("nr", "ns", "nz", "nt")

    fun evaluate(
        term: String,
        posTag: String?,
        rank: (String) -> Int?,
    ): Decision {
        if (StopWords.contains(term)) return Decision.Rejected("stopword_blacklist")
        if (term.length < 2) return Decision.Rejected("too_short")
        if (term.length > 12) return Decision.Rejected("too_long")
        if (term.all { (it.isAsciiDigit()) || it.isPunctuationLike() || it.isWhitespace() }) {
            return Decision.Rejected("no_lexical_content")
        }

        val b = ScoreBreakdown()
        val r = rank(term)
        if (r == null || r > VoiceCorrectionConfig.VOCAB_BACKGROUND_RANK_THRESHOLD) {
            b.backgroundRank = 2
        }
        if (posTag != null && posTag in PROPER_NOUN_TAGS) b.posTag = 2

        val hasLatin = term.any { it.isAsciiLetter() }
        val hasDigit = term.any { it.isAsciiDigit() }
        if (hasLatin || hasDigit) b.hasLatinOrDigit = 1
        if (term.length in 2..6) b.lengthOk = 1

        return if (b.total >= VoiceCorrectionConfig.VOCAB_MIN_SCORE) {
            Decision.Accepted(b.total, b)
        } else {
            Decision.Rejected("low_score(${b.total})")
        }
    }

    fun candidates(
        text: String,
        posTag: (String) -> List<Pair<String, String>>,
        segment: (String) -> List<String>,
    ): List<Triple<String, Int, String?>> {
        val tagged = runCatching { posTag(text) }.getOrElse { emptyList() }
        if (tagged.isNotEmpty()) {
            val counts = LinkedHashMap<String, Int>()
            val tags = HashMap<String, String>()
            for ((token, tag) in tagged) {
                counts[token] = (counts[token] ?: 0) + 1
                tags[token] = tag
            }
            return counts.map { (t, n) -> Triple(t, n, tags[t]) }
        }
        val counts = LinkedHashMap<String, Int>()
        for (token in runCatching { segment(text) }.getOrElse { emptyList() }) {
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts.map { (t, n) -> Triple(t, n, null) }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    private fun Char.isPunctuationLike(): Boolean =
        !isLetterOrDigit() && !isWhitespace()
}

object StopWords {

    val chinese: Set<String> = setOf(
        "的", "了", "是", "我", "你", "他", "她", "它", "我们", "你们", "他们",
        "这", "那", "这个", "那个", "什么", "怎么", "为什么", "哪里", "谁",
        "有", "没有", "不", "也", "都", "很", "太", "就", "还", "又", "再", "才", "只",
        "可以", "能", "会", "要", "想", "需要", "应该", "可能", "或者", "但是", "因为", "所以",
        "然后", "而且", "不过", "虽然", "如果", "继续", "好的", "嗯", "就是", "其实", "反正",
        "一下", "一点", "一样", "一起", "现在", "今天", "明天", "昨天",
        "说", "讲", "看", "听", "做", "用", "给", "让", "把", "被", "在", "和", "与", "对",
    )

    val english: Set<String> = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "that", "this", "these", "those",
        "is", "are", "was", "were", "be", "been", "being", "am",
        "do", "does", "did", "done", "have", "has", "had",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their",
        "to", "of", "in", "on", "at", "by", "for", "with", "from", "into", "about", "as",
        "not", "no", "yes", "ok", "okay", "so", "just", "very", "too", "also", "only",
        "what", "which", "who", "when", "where", "why", "how",
        "there", "here", "some", "any", "all", "more", "most", "one", "two",
        "get", "got", "let", "make", "made", "use", "used", "like", "want", "need",
        "please", "thanks", "thank", "hi", "hello", "now", "still", "even", "up", "out", "over",
    )

    fun contains(term: String): Boolean =
        chinese.contains(term) || english.contains(term.lowercase())
}

class BackgroundWordFrequency private constructor(private val ranks: Map<String, Int>) {

    fun rank(of: String): Int? = ranks[of]

    val size: Int get() = ranks.size

    companion object {
        private const val TAG = "BackgroundWordFreq"

        @Volatile
        private var instance: BackgroundWordFrequency? = null

        fun getInstance(context: Context): BackgroundWordFrequency {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }
        }

        private fun load(context: Context): BackgroundWordFrequency {
            val map = runCatching {
                context.resources.openRawResource(R.raw.zh_background_wordfreq)
                    .bufferedReader().useLines { lines -> parse(lines) }
            }.getOrElse {
                Log.w(TAG, "word-frequency list unavailable — every term scores as rare", it)
                emptyMap()
            }
            Log.i(TAG, "loaded ${map.size} background terms")
            return BackgroundWordFrequency(map)
        }

        internal fun parse(lines: Sequence<String>): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size != 2) continue
                val rank = parts[1].toIntOrNull() ?: continue
                out[parts[0]] = rank
            }
            return out
        }
    }
}
