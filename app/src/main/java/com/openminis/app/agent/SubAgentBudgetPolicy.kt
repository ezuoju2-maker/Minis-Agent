package com.openminis.app.agent

object SubAgentBudgetPolicy {
    const val DEFAULT_TURNS = 60

    const val MIN_TURNS = 8

    const val MAX_TURNS = 200

    const val MIN_WARNING_LEAD = 3

    fun coerceTurns(requested: Int?): Int {
        if (requested == null) return DEFAULT_TURNS
        return requested.coerceIn(MIN_TURNS, MAX_TURNS)
    }

    fun warningLead(turnCap: Int): Int = maxOf(MIN_WARNING_LEAD, turnCap / 5)

    fun shouldWarn(remaining: Int, turnCap: Int): Boolean =
        remaining in 1..warningLead(turnCap)
}
