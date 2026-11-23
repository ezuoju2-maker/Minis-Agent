package com.openminis.app.shared

import android.content.Context

class TextSegmenter private constructor(context: Context) {

    private val jieba = JiebaEngine(context)

    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return if (isMajorityChinese(text)) {
            val words = jieba.segment(text)
            words.ifEmpty { SystemSegmentEngine.segment(text) }
        } else {
            SystemSegmentEngine.segment(text)
        }
    }

    fun segmentForSearch(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return if (isMajorityChinese(text)) {
            val words = jieba.segmentForSearch(text)
            words.ifEmpty { SystemSegmentEngine.segmentForSearch(text) }
        } else {
            SystemSegmentEngine.segmentForSearch(text)
        }
    }

    fun posTag(text: String): List<Pair<String, String>> {
        if (text.isBlank() || !isMajorityChinese(text)) return emptyList()
        return jieba.posTag(text)
    }

    companion object {
        internal const val SAMPLE_WINDOW = 200

        internal const val CHINESE_THRESHOLD = 0.5

        internal const val KANA_RANGE_START = 0x3040

        internal const val KANA_RANGE_END = 0x30FF

        @Volatile
        private var instance: TextSegmenter? = null

        fun getInstance(context: Context): TextSegmenter =
            instance ?: synchronized(this) {
                instance ?: TextSegmenter(context.applicationContext).also { instance = it }
            }

        internal fun isMajorityChinese(text: String): Boolean {
            var hanCount = 0
            var nonSpaceCount = 0
            var seen = 0
            for (ch in text) {
                if (seen >= SAMPLE_WINDOW) break
                seen++
                if (ch.code in KANA_RANGE_START..KANA_RANGE_END) return false
                if (ch.isWhitespace()) continue
                nonSpaceCount++
                if (ch.code in 0x4E00..0x9FFF) hanCount++
            }
            if (nonSpaceCount == 0) return false
            return hanCount.toDouble() / nonSpaceCount >= CHINESE_THRESHOLD
        }
    }
}
