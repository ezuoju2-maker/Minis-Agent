package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBubbleTargetTest {

    private fun msg(role: String, content: String) =
        ChatMessage(id = "m1", role = role, content = content)

    @Test
    fun `a typed message is a user bubble`() {
        assertTrue(msg("user", "帮我查一下今天的天气").rendersAsUserBubble())
    }

    @Test
    fun `a message that only talks about the markers is still a bubble`() {
        assertTrue(msg("user", "what does <agent_callback> mean?").rendersAsUserBubble())
        assertTrue(msg("user", "the scheduled_task tag confused me").rendersAsUserBubble())
    }

    @Test
    fun `a sub agent callback is not a user bubble`() {
        assertFalse(
            msg("user", "<agent_callback kind=\"finished\" job=\"j1\">done</agent_callback>")
                .rendersAsUserBubble(),
        )
        assertFalse(msg("user", "<agent_callback>bare</agent_callback>").rendersAsUserBubble())
    }

    @Test
    fun `a scheduled task prompt is not a user bubble`() {
        assertFalse(
            msg("user", "<scheduled_task id=\"t1\">check the build</scheduled_task>")
                .rendersAsUserBubble(),
        )
    }

    @Test
    fun `an id-less scheduled marker still renders as a bubble`() {
        assertTrue(msg("user", "<scheduled_task>bare</scheduled_task>").rendersAsUserBubble())
    }

    @Test
    fun `assistant and system messages are never user bubbles`() {
        assertFalse(msg("assistant", "here you go").rendersAsUserBubble())
        assertFalse(msg("system", "you are a helpful assistant").rendersAsUserBubble())
    }
}
