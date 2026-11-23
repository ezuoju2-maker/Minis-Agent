package com.openminis.app.provider

object DsmlLeak {

    val MARKERS: List<String> = listOf(
        "｜DSML｜",
        "|DSML|",
        "<｜tool▁calls▁begin｜>",
        "<｜tool▁call▁begin｜>",
        "<｜tool▁sep｜>",
        "<｜tool▁call▁end｜>",
        "<｜tool▁calls▁end｜>",
        "tool_calls_begin",
        "tool_call_begin",
    )

    const val MAX_RETRIES: Int = 2

    fun marker(reasoning: String?): String? {
        if (reasoning.isNullOrEmpty()) return null
        return MARKERS.firstOrNull { reasoning.contains(it) }
    }

    fun isLeak(turnHasText: Boolean, hasToolCalls: Boolean, finishReason: String?, reasoning: String?): Boolean {
        if (turnHasText || hasToolCalls) return false
        if (finishReason != "stop" && finishReason != "end_turn") return false
        return marker(reasoning) != null
    }

    sealed class Step {
        data class Retry(val attempt: Int) : Step()

        object GiveUp : Step()
    }

    fun next(retriesSoFar: Int): Step =
        if (retriesSoFar < MAX_RETRIES) Step.Retry(retriesSoFar + 1) else Step.GiveUp
}
