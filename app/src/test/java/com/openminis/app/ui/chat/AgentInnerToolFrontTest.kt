package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInnerToolFrontTest {

    private val src by lazy {
        File("src/main/java/com/openminis/app/ui/chat/ChatComposerWidgets.kt").readText()
    }

    @Test
    fun `the delegate branch substitutes the child's live tool block`() {
        val branch = src.substringAfter("HelperRunner.TOOL_NAME -> {")
            .substringBefore("else -> {")
        assertTrue(
            "must look up the child's live tool",
            branch.contains("rememberChildLiveToolBlock(childId)"),
        )
        assertTrue(
            "must recurse into the generic renderer so every tool type is covered",
            branch.contains("ToolPreviewThumbnail("),
        )
        assertTrue(
            "must fall back to the agent card when the child is between tools",
            branch.contains("HelperThumbContent(block)"),
        )
    }

    @Test
    fun `the recursion is depth-capped at one level`() {
        val branch = src.substringAfter("HelperRunner.TOOL_NAME -> {")
            .substringBefore("else -> {")
        assertTrue("must gate on depth", branch.contains("punchThroughDepth < 1"))
        assertTrue("must increment on recurse", branch.contains("punchThroughDepth = punchThroughDepth + 1"))
        assertTrue("the parameter must default to 0", src.contains("punchThroughDepth: Int = 0"))
    }

    @Test
    fun `the glow survives the substitution`() {
        val decl = src.substringAfter("val subAgentLive =").substringBefore("val subAgentGlow")
        assertTrue(
            "a punched-through tile must count as a sub agent tile",
            decl.contains("punchThroughDepth > 0"),
        )
        assertTrue(
            "the original agent-card case must still qualify",
            decl.contains("HelperRunner.TOOL_NAME"),
        )
    }

    @Test
    fun `the observer never constructs a view model`() {
        val fn = src.substringAfter("fun rememberChildLiveToolBlock")
            .substringBefore("@Composable\nprivate fun ToolPreviewThumbnail")
        assertTrue("must use the non-constructing lookup", fn.contains("ChatViewModelStore.existing("))
        assertFalse("must not build one", fn.contains("ViewModelProvider"))
        assertFalse("must not use ownerFor", fn.contains("ownerFor"))
    }

    @Test
    fun `the observer merges the streaming side-channel`() {
        val fn = src.substringAfter("fun rememberChildLiveToolBlock")
            .substringBefore("@Composable\nprivate fun ToolPreviewThumbnail")
        assertTrue("must observe the stream", fn.contains("streamingById"))
        assertTrue(
            "must overlay the side-channel onto the messages",
            fn.contains("streamingOverlaySubset(msgs, streaming)") ||
                fn.contains("mergeStreamingOverlay(msgs, streaming)"),
        )
    }

    @Test
    fun `only an ACTIVE child tool is surfaced`() {
        val fn = src.substringAfter("fun rememberChildLiveToolBlock")
            .substringBefore("@Composable\nprivate fun ToolPreviewThumbnail")
        for (st in listOf("RUNNING", "STREAMING", "PENDING")) {
            assertTrue("must accept $st", fn.contains("ToolBlockStatus.$st"))
        }
        assertTrue("must filter to tool_use blocks", fn.contains("kind == \"tool_use\""))
    }
}
