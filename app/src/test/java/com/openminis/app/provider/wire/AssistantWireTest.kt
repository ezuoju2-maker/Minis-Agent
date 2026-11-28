package com.openminis.app.provider.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantWireTest {

    @Test
    fun visibleTextIsKept() {
        assertEquals("done", AssistantWire.contentFor("done", hasToolCalls = false))
        assertEquals("done", AssistantWire.contentFor("done", hasToolCalls = true))
    }

    @Test
    fun blankContentIsOmittedOnToolCallTurns() {
        assertNull(AssistantWire.contentFor("   ", hasToolCalls = true))
        assertNull(AssistantWire.contentFor("", hasToolCalls = true))
    }

    @Test
    fun blankContentStaysEmptyWhenNoToolCalls() {
        assertEquals("", AssistantWire.contentFor("", hasToolCalls = false))
    }

    @Test
    fun toolCallTurnLegality() {
        assertFalse(AssistantWire.isLegalToolCallTurn("", hasToolCalls = true))
        assertTrue(AssistantWire.isLegalToolCallTurn("calling", hasToolCalls = true))
        assertTrue(AssistantWire.isLegalToolCallTurn("", hasToolCalls = false))
    }

    @Test
    fun textBlockSkipsBlanks() {
        assertNull(AssistantWire.textBlock(""))
        assertNull(AssistantWire.textBlock(" \n "))
        assertEquals("hi", AssistantWire.textBlock("hi"))
    }

    @Test
    fun thinkingBlockSkipsBlanks() {
        assertNull(AssistantWire.thinkingBlock(null))
        assertNull(AssistantWire.thinkingBlock(""))
        assertEquals("reasoned", AssistantWire.thinkingBlock("reasoned"))
    }

    @Test
    fun reasoningEchoOnlyWhenEnabledAndPresent() {
        assertEquals("why", AssistantWire.reasoningFor("why", thinkingEnabled = true, placeholderAllowed = true))
        assertEquals("why", AssistantWire.reasoningFor("why", thinkingEnabled = true, placeholderAllowed = false))
        assertNull(AssistantWire.reasoningFor("why", thinkingEnabled = false, placeholderAllowed = true))
        assertEquals("", AssistantWire.reasoningFor(null, thinkingEnabled = true, placeholderAllowed = true))
        assertNull(AssistantWire.reasoningFor(null, thinkingEnabled = true, placeholderAllowed = false))
    }
}
