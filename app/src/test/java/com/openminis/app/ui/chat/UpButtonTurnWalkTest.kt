package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpButtonTurnWalkTest {

    private data class Msg(val id: String, val role: String)

    private fun keyMessageId(key: String?): String? =
        key?.split(':')?.getOrNull(1)?.takeIf { it.isNotEmpty() }

    private fun pickTarget(
        messages: List<Msg>,
        topRowKey: String?,
        lastJumpedUserId: String?,
    ): String? {
        val userIds = messages.filter { it.role == "user" }.map { it.id }
        if (userIds.isEmpty()) return null
        val topMessageId = keyMessageId(topRowKey)
        val topMsgIdx = topMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val currentAnchor = messages.take(topMsgIdx + 1).lastOrNull { it.role == "user" }?.id
            ?: userIds.first()
        val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
        val pos = userIds.indexOf(walkFrom)
        return if (lastJumpedUserId == walkFrom && pos > 0) userIds[pos - 1] else walkFrom
    }

    private val convo = listOf(
        Msg("u1", "user"), Msg("a1", "assistant"),
        Msg("u2", "user"), Msg("a2", "assistant"),
        Msg("u3", "user"), Msg("a3", "assistant"),
    )

    @Test
    fun `first tap anchors to the current turn rather than stepping back`() {
        val target = pickTarget(convo, topRowKey = "mdblock:a3", lastJumpedUserId = null)
        assertEquals("u3", target)
    }

    @Test
    fun `a repeated tap walks one turn further back`() {
        val target = pickTarget(convo, topRowKey = "user:u3", lastJumpedUserId = "u3")
        assertEquals("u2", target)
    }

    @Test
    fun `walking continues turn by turn`() {
        assertEquals("u1", pickTarget(convo, topRowKey = "user:u2", lastJumpedUserId = "u2"))
    }

    @Test
    fun `the first turn is a floor - no overscroll past it`() {
        val target = pickTarget(convo, topRowKey = "user:u1", lastJumpedUserId = "u1")
        assertEquals("u1", target)
    }

    private val longConvo = (1..5).flatMap {
        listOf(Msg("u$it", "user"), Msg("a$it", "assistant"))
    }

    @Test
    fun `at the end of content the walk still advances instead of oscillating`() {
        val target = pickTarget(
            longConvo,
            topRowKey = "user:u1",
            lastJumpedUserId = "u4",
        )
        assertEquals("u3", target)
    }

    @Test
    fun `at the end of content a fresh tap does not skip ahead`() {
        val target = pickTarget(
            convo,
            topRowKey = "user:u2",
            lastJumpedUserId = null,
        )
        assertEquals("u2", target)
    }

    @Test
    fun `a stale lastJumped that is not the current anchor re-anchors`() {
        val target = pickTarget(convo, topRowKey = "mdblock:a2", lastJumpedUserId = null)
        assertEquals("u2", target)
    }

    @Test
    fun `row keys with an id suffix still resolve to the message id`() {
        assertEquals("a3", keyMessageId("mdblock:a3:text_a3_0:1"))
        assertEquals("u3", keyMessageId("user:u3"))
        assertEquals("a3", keyMessageId("thinking:a3"))
        assertNull(keyMessageId("__resume_banner__"))
    }

    @Test
    fun `an unloaded top row anchors to the oldest loaded turn`() {
        val target = pickTarget(convo, topRowKey = "mdblock:NOT_LOADED", lastJumpedUserId = null)
        assertEquals("u1", target)
    }

    private fun seekRowIndex(
        rows: List<String>,
        viewport: IntRange,
        targetKey: String,
        strideFromHighestOnly: Boolean,
    ): Int? {
        rows.indexOf(targetKey).let { if (it in viewport) return it }
        if (strideFromHighestOnly) {
            var hi = viewport.last
            val stride = viewport.count().coerceAtLeast(1)
            var guard = 0
            while (guard++ < 60) {
                val next = (hi + stride).coerceAtMost(rows.lastIndex)
                if (next <= hi) return null
                val window = next..(next + stride - 1).coerceAtMost(rows.lastIndex)
                val found = rows.indexOf(targetKey)
                if (found in window) return found
                hi = window.last
            }
            return null
        }
        return rows.indexOf(targetKey).takeIf { it >= 0 }
    }

    @Test
    fun `the seek finds a target that sits just below the viewport`() {
        val rows = List(47) { "row$it" }.toMutableList().also { it[9] = "user:u2" }
        val viewport = 0..8

        assertNull(
            "old stride seek must miss it (this is the reported bug)",
            seekRowIndex(rows, viewport, "user:u2", strideFromHighestOnly = true),
        )
        assertEquals(
            9,
            seekRowIndex(rows, viewport, "user:u2", strideFromHighestOnly = false),
        )
    }

    @Test
    fun `the seek still finds a target far above the viewport`() {
        val rows = List(47) { "row$it" }.toMutableList().also { it[46] = "user:u1" }
        assertEquals(
            46,
            seekRowIndex(rows, 0..8, "user:u1", strideFromHighestOnly = false),
        )
    }

    @Test
    fun `a conversation with no user turns yields no target`() {
        val noUsers = listOf(Msg("a1", "assistant"), Msg("a2", "assistant"))
        assertNull(pickTarget(noUsers, topRowKey = "mdblock:a2", lastJumpedUserId = null))
    }
}
