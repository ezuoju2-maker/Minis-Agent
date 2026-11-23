package com.openminis.app.speech.correction

object CorrectionAdmission {

    object Config {
        const val PHONETIC_SIMILARITY = 0.6

        const val MAX_EDIT_LOCALITY = 0.6

        const val MAX_ACRONYM_LENGTH_FRACTION = 0.9

        const val LATIN_SKELETON_SIMILARITY = 0.30
    }

    sealed class Verdict {
        data class Homophone(val sim: Double) : Verdict()

        object Acronym : Verdict()

        object DigitNorm : Verdict()

        object LatinPhonetic : Verdict()

        data class RejectedReword(val sim: Double) : Verdict()

        object RejectedReorder : Verdict()

        data class RejectedTooGlobal(val locality: Double) : Verdict()

        val isAdmitted: Boolean
            get() = when (this) {
                is Homophone, is Acronym, is DigitNorm, is LatinPhonetic -> true
                is RejectedReword, is RejectedReorder, is RejectedTooGlobal -> false
            }

        val reason: String
            get() = when (this) {
                is Homophone -> "homophone(sim=%.2f)".format(sim)
                is Acronym -> "acronym"
                is DigitNorm -> "digit_norm"
                is LatinPhonetic -> "latin_phonetic"
                is RejectedReword -> "reword(sim=%.2f)".format(sim)
                is RejectedReorder -> "reorder"
                is RejectedTooGlobal -> "too_global(loc=%.2f)".format(locality)
            }
    }

    fun judge(
        from: String,
        to: String,
        normalizer: PhoneticNormalizer,
        sentenceLength: Int,
    ): Verdict {
        val a = from.trim()
        val b = to.trim()

        val spanLen = maxOf(a.length, b.length)
        val locality = if (sentenceLength > 0) spanLen.toDouble() / sentenceLength.toDouble() else 1.0
        if (locality > Config.MAX_EDIT_LOCALITY) {
            return Verdict.RejectedTooGlobal(locality)
        }

        if (isPureReorder(a, b)) return Verdict.RejectedReorder

        val keyA = normalizer.normalize(a)
        val keyB = normalizer.normalize(b)

        val sim = PinyinNormalizer.similarity(keyA, keyB)
        if (sim >= Config.PHONETIC_SIMILARITY && keyA.isNotEmpty()) {
            return Verdict.Homophone(sim)
        }

        if (isDigitTermNormalization(a, b)) return Verdict.DigitNorm

        if (isAcronymRelated(keyA, keyB)) return Verdict.Acronym

        if (isLatinOrigin(a) && isLatinOrigin(b) && isLatinPhoneticSimilar(keyA, keyB)) {
            return Verdict.LatinPhonetic
        }

        return Verdict.RejectedReword(sim)
    }


    fun isPureReorder(a: String, b: String): Boolean {
        fun bag(s: String): Map<Char, Int> {
            val m = HashMap<Char, Int>()
            for (c in s) if (!c.isWhitespace()) m[c] = (m[c] ?: 0) + 1
            return m
        }
        val ba = bag(a)
        val bb = bag(b)
        if (ba.isEmpty() || ba != bb) return false
        return a != b
    }

    fun isAcronymRelated(keyA: String, keyB: String): Boolean {
        val short = if (keyA.length <= keyB.length) keyA else keyB
        val long = if (keyA.length <= keyB.length) keyB else keyA
        if (short.isEmpty() || short.length >= long.length) return false
        if (short.length.toDouble() / long.length.toDouble() >= Config.MAX_ACRONYM_LENGTH_FRACTION) {
            return false
        }
        var idx = 0
        for (ch in short) {
            val found = long.indexOf(ch, idx)
            if (found < 0) return false
            idx = found + 1
        }
        return true
    }

    fun isDigitTermNormalization(a: String, b: String): Boolean {
        val chineseNumerals = "零一二三四五六七八九十百千万两".toSet()
        fun hasChineseNumeral(s: String) = s.any { it in chineseNumerals }
        fun hasArabicDigit(s: String) = s.any { it in '0'..'9' }
        val aCn = hasChineseNumeral(a)
        val bCn = hasChineseNumeral(b)
        val aDigit = hasArabicDigit(a)
        val bDigit = hasArabicDigit(b)
        return (aCn && bDigit && !bCn) || (bCn && aDigit && !aCn)
    }

    fun isLatinOrigin(s: String): Boolean {
        var sawLetter = false
        for (ch in s) {
            when {
                ch.isLetter() -> {
                    if (ch.code > 0x7F) return false
                    sawLetter = true
                }
                ch.isWhitespace() -> {}
                ch in '0'..'9' -> {}
                ch == '-' || ch == '_' || ch == '.' -> {}
                else -> return false
            }
        }
        return sawLetter
    }

    fun isLatinPhoneticSimilar(keyA: String, keyB: String): Boolean {
        val vowels = setOf('a', 'e', 'i', 'o', 'u')

        fun syllableCount(s: String): Int {
            var count = 0
            var inVowel = false
            for (c in s) {
                if (c in vowels) {
                    if (!inVowel) { count++; inVowel = true }
                } else {
                    inVowel = false
                }
            }
            return count
        }

        val scA = syllableCount(keyA)
        val scB = syllableCount(keyB)
        if (scA == 0 || scB == 0 || scA != scB) return false

        val skelA = keyA.filter { it !in vowels }
        val skelB = keyB.filter { it !in vowels }
        if (skelA.isEmpty() || skelB.isEmpty()) return false

        val anchored = (0 until minOf(skelA.length, skelB.length)).any { skelA[it] == skelB[it] }
        if (!anchored) return false

        return PinyinNormalizer.similarity(skelA, skelB) >= Config.LATIN_SKELETON_SIMILARITY
    }
}
