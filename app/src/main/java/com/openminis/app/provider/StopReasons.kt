package com.openminis.app.provider

object StopReasons {
    const val REFUSAL = "refusal"
    const val CONTENT_FILTER = "content_filter"

    fun isRefusal(stopReason: String?): Boolean =
        stopReason == REFUSAL || stopReason == CONTENT_FILTER

    fun isMaxTokens(stopReason: String?): Boolean =
        stopReason == "length" || stopReason == "max_tokens" || stopReason == "max_output_tokens"

    fun refusalErrorText(categoryMessage: String?, genericMessage: String, explanation: String?): String {
        val base = categoryMessage?.takeIf { it.isNotBlank() } ?: genericMessage
        val detail = explanation?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        return base + "\n\n" + detail
    }
}
