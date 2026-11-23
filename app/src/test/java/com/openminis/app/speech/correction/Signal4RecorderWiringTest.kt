package com.openminis.app.speech.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Signal4RecorderWiringTest {

    private val latin = object : PhoneticNormalizer {
        override val locale: String = "en"
        override fun normalize(text: String): String =
            text.lowercase().split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString("")
    }

    @Test
    fun `linux to minis inside a realistic sentence is admitted as latin_phonetic`() {
        val sentence = "run linux in the sandbox"
        val verdict = CorrectionAdmission.judge(
            from = "linux",
            to = "minis",
            normalizer = latin,
            sentenceLength = sentence.length,
        )
        assertTrue("should be admitted, got $verdict", verdict.isAdmitted)
        assertEquals(CorrectionAdmission.Verdict.LatinPhonetic, verdict)
        assertEquals("latin_phonetic", verdict.reason)
    }

    @Test
    fun `the locality guard still outranks signal 4`() {
        val verdict = CorrectionAdmission.judge(
            from = "linux",
            to = "minis",
            normalizer = latin,
            sentenceLength = 5,
        )
        assertTrue(verdict is CorrectionAdmission.Verdict.RejectedTooGlobal)
    }
}
