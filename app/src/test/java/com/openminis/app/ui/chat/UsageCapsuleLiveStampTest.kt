package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCapsuleLiveStampTest {

    private val usage = ChatTokenUsage(
        inputTokens = 120, outputTokens = 30,
        cacheCreationTokens = 0, cacheReadTokens = 0, latestContextTokens = 150,
    )

    private fun user(id: String) = ChatMessage(id = id, role = "user", content = "say ALPHA")

    private fun reply(id: String, streaming: Boolean, withUsage: Boolean) = ChatMessage(
        id = id, role = "assistant", content = "",
        isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "$id-t", kind = "text", content = "ALPHA")),
        tokenUsage = if (withUsage) usage else null,
        completedAt = if (withUsage) 1_700_000_000_000L else null,
    )

    private fun usageRows(vararg msgs: ChatMessage) =
        buildFlatChatItems(msgs.toList()).filterIsInstance<FlatChatItem.AssistantUsage>()


    @Test
    fun `a finished reply with usage yields exactly one capsule row`() {
        val rows = usageRows(user("u1"), reply("a1", streaming = false, withUsage = true))
        assertEquals(1, rows.size)
        assertEquals("a1", rows[0].messageId)
        assertEquals(usage, rows[0].usage)
    }

    @Test
    fun `a finished reply WITHOUT usage yields no row — the pre-fix live state`() {
        val rows = usageRows(user("u1"), reply("a1", streaming = false, withUsage = false))
        assertTrue("no usage → no capsule row; this is the bug's failure mode", rows.isEmpty())
    }

    @Test
    fun `usage on a still-streaming reply is held back until the stream ends`() {
        assertTrue(usageRows(user("u1"), reply("a1", streaming = true, withUsage = true)).isEmpty())
        assertEquals(1, usageRows(user("u1"), reply("a1", streaming = false, withUsage = true)).size)
    }


    private val vmSrc by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("missing ChatViewModel source", f.exists())
        f.readText()
    }

    @Test
    fun `persistAssistantTurn stamps tokenUsage and completedAt onto the live bubble`() {
        val body = vmSrc.substringAfter("private suspend fun persistAssistantTurn(")
            .substringBefore("@Deprecated")
        assertTrue("must accept the UI bubble id", body.contains("uiAssistantId: String?"))
        assertTrue(
            "must derive usage from the persisted entity, exactly like toChatMessages()",
            body.contains("ChatTokenUsage.parse(entity.tokenUsage)"),
        )
        assertTrue(
            "must stamp both fields onto the matching live ChatMessage",
            body.contains("m.copy(tokenUsage = stampedUsage ?: m.tokenUsage, completedAt = entity.createdAt)"),
        )
        assertTrue("the _messages write must happen on Main", body.contains("withContext(Dispatchers.Main)"))
    }

    @Test
    fun `a turn without usage keeps the capsule an earlier turn stamped`() {
        val body = vmSrc.substringAfter("private suspend fun persistAssistantTurn(")
            .substringBefore("@Deprecated")
        assertTrue(body.contains("tokenUsage = stampedUsage ?: m.tokenUsage"))
        assertTrue("must not overwrite with a possibly-null value",
            !body.contains("m.copy(tokenUsage = stampedUsage,"))
        assertTrue("the reload merge applies the same rule",
            vmSrc.contains("tokenUsage = msg.tokenUsage ?: prev.tokenUsage,"))
    }

    @Test
    fun `both agent-loop callers hand the bubble id to the persist step`() {
        val calls = Regex("persistAssistantTurn\\(turnParts, lastUsage, turnReasoningContent, blockMeta[^)]*\\)")
            .findAll(vmSrc).map { it.value }.toList()
        assertEquals("expected the final-turn and tool-turn callers", 2, calls.size)
        for (c in calls) {
            assertTrue(
                "caller must pass uiAssistantId = assistantId, or that path's bubble never gets its capsule: $c",
                c.contains("uiAssistantId = assistantId"),
            )
        }
    }

    @Test
    fun `the live stamp mirrors the load path field for field`() {
        val load = vmSrc.substringAfter("toChatMessages(): List<ChatMessage>")
        assertTrue(load.contains("ChatTokenUsage.parse(entity.tokenUsage)"))
        assertTrue(load.contains("completedAt = if (entity.role == \"assistant\") entity.createdAt else null"))
    }
}
