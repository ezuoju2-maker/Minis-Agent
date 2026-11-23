package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarReserveCompactionTest {

    private fun tool(id: String, kind: String = "tool_use", status: ToolBlockStatus? = ToolBlockStatus.SUCCESS) =
        AssistantBlock(id = id, kind = kind, toolStatus = status)

    private fun reply(id: String, compacted: Boolean, vararg blocks: AssistantBlock) =
        ChatMessage(id = id, role = "assistant", content = "", toolBlocks = blocks.toList(), isCompactedHistory = compacted)

    private val cases = mapOf(
        "empty" to emptyList(),
        "tools only above the divider" to listOf(
            reply("old", compacted = true, tool("a"), tool("b")),
            ChatMessage(id = "div", role = "system", content = "2 messages compacted"),
            ChatMessage(id = "u", role = "user", content = "hi"),
            reply("new", compacted = false, AssistantBlock(id = "txt", kind = "text", content = "hello")),
        ),
        "tools below the divider" to listOf(
            reply("old", compacted = true, tool("a")),
            reply("new", compacted = false, tool("b")),
        ),
        "thinking and info only" to listOf(
            reply("r", compacted = false, tool("t", kind = "thinking"), tool("i", kind = "info")),
        ),
        "text blocks have no status" to listOf(
            reply("r", compacted = false, tool("x", status = null)),
        ),
        "a running tool" to listOf(
            reply("r", compacted = false, tool("x", status = ToolBlockStatus.RUNNING)),
        ),
        "tool blocks on a user row" to listOf(
            ChatMessage(id = "u", role = "user", content = "", toolBlocks = listOf(tool("x"))),
        ),
    )

    @Test
    fun `the reserve predicate is true exactly when the bar has blocks to show`() {
        cases.forEach { (name, messages) ->
            assertEquals(name, floatingToolBarBlocks(messages).isNotEmpty(), hasFloatingToolBarBlocks(messages))
        }
        assertFalse(hasFloatingToolBarBlocks(cases.getValue("tools only above the divider")))
        assertTrue(hasFloatingToolBarBlocks(cases.getValue("tools below the divider")))
    }

    @Test
    fun `the chat screen derives the reserve from the shared predicate`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        val reserve = screen.substringAfter("val hasFloatingTools = remember(messages, streamingById) {")
            .substringBefore("val bottomReserve =")
        assertTrue(reserve.contains("hasFloatingToolBarBlocks(merged)"))
        assertFalse("no second copy of the bar's filter", reserve.contains("msg.toolBlocks.any"))
    }
}
