package com.openminis.app.shared

import android.icu.text.BreakIterator
import java.util.Locale

internal object SystemSegmentEngine {

    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(text)

        val result = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)
            if (isMeaningfulToken(token)) {
                result.add(token)
            }
            start = end
            end = iterator.next()
        }
        return result
    }

    fun segmentForSearch(text: String): List<String> = segment(text)

    private fun isMeaningfulToken(token: String): Boolean {
        if (token.isBlank()) return false
        return token.any { it.isLetterOrDigit() }
    }
}
