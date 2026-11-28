package com.openminis.app.agent.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionGoalsCodecTest {

    @Test
    fun roundTripsGoal() {
        val goal = SessionGoals.Goal("All unit tests pass and the README matches the new flags.", 1_700_000_000_000L, 3)
        val decoded = SessionGoals.decode(SessionGoals.encode(goal))!!
        assertEquals(goal.text, decoded.text)
        assertEquals(goal.createdAt, decoded.createdAt)
        assertEquals(3, decoded.continuations)
    }

    @Test
    fun blankTextIsNotAGoal() {
        assertNull(SessionGoals.decode("""{"text":"   ","createdAt":1}"""))
    }

    @Test
    fun brokenJsonIsIgnored() {
        assertNull(SessionGoals.decode("not json"))
    }

    @Test
    fun missingContinuationsDefaultsToZero() {
        val decoded = SessionGoals.decode("""{"text":"ship it","createdAt":42}""")!!
        assertEquals(0, decoded.continuations)
    }
}
