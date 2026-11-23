package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingAutoCollapseTest {

    @Test
    fun `a block still streaming never auto-collapses`() {
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = null))
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = true))
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = false))
    }

    @Test
    fun `an untouched block folds away when the model stops thinking`() {
        assertTrue(shouldAutoCollapseThinking(isStreaming = false, userIntent = null))
    }

    @Test
    fun `a block the user expanded still folds away at stream end`() {
        assertTrue(shouldAutoCollapseThinking(isStreaming = false, userIntent = true))
    }

    @Test
    fun `a block the user collapsed is left alone`() {
        assertFalse(shouldAutoCollapseThinking(isStreaming = false, userIntent = false))
    }

    @Test
    fun `collapse then re-expand while streaming still collapses at the end`() {
        var intent: Boolean? = null
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = intent))

        intent = false
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = intent))

        intent = true
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = intent))

        assertTrue(shouldAutoCollapseThinking(isStreaming = false, userIntent = intent))
    }

    @Test
    fun `expand then collapse while streaming stays collapsed at the end`() {
        var intent: Boolean? = true
        intent = false
        assertFalse(shouldAutoCollapseThinking(isStreaming = false, userIntent = intent))
    }
}
