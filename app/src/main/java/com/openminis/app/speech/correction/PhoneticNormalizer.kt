package com.openminis.app.speech.correction

interface PhoneticNormalizer {
    val locale: String

    fun normalize(text: String): String
}

object PhoneticNormalizerRegistry {

    fun normalizer(locale: String): PhoneticNormalizer? =
        when (normalizedLocaleKey(locale)) {
            "zh" -> PinyinNormalizer
            else -> null
        }

    fun normalizedLocaleKey(locale: String): String {
        val lower = locale.lowercase()
        val base = lower.split('-', '_').firstOrNull() ?: return lower
        return base
    }
}

object PinyinNormalizer : PhoneticNormalizer {

    override val locale: String = "zh"

    private val transliterator: android.icu.text.Transliterator? by lazy {
        runCatching {
            android.icu.text.Transliterator.getInstance(
                "Han-Latin; nfd; [:nonspacing mark:] remove",
            )
        }.onFailure {
            android.util.Log.e(TAG, "ICU Han-Latin unavailable — phonetic keys disabled", it)
        }.getOrNull()
    }

    override fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val t = transliterator ?: return ""
        val latin = runCatching { t.transliterate(trimmed) }.getOrNull() ?: return ""
        return latin.lowercase().split(' ', '\t', '\n')
            .filter { it.isNotEmpty() }
            .joinToString("")
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length).toDouble()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    private const val TAG = "PinyinNormalizer"
}
