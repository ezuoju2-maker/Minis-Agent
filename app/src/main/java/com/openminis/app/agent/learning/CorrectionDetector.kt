package com.openminis.app.agent.learning

object CorrectionDetector {

    private val phrases = listOf(
        "不对", "不是这样", "不是这个意思", "错了", "搞错", "应该是", "应该用",
        "以后都", "以后别", "以后要", "别再", "不要再", "记住", "按我说的",
        "我说的是", "重新做", "你忘了", "跟你说过", "不是让你", "别自作主张",
        "wrong", "that's not", "not what i", "i said", "instead", "from now on",
        "don't do", "stop doing", "never do", "always use", "remember that",
        "you forgot", "as i told you",
    )

    fun detect(text: String): Boolean {
        val lower = text.lowercase()
        return phrases.any { lower.contains(it) }
    }

    fun nudge(): String =
        "The user just corrected you. Before continuing, call lesson_write with ONE reusable rule " +
            "(imperative, 1 sentence, no time/context that only applies to this chat — e.g. \"Reports go to " +
            "/var/minis/out, not the chat body\"), then apply it right away. If the correction contradicts an " +
            "older lesson, write the new rule anyway; the store keeps the newer one on the same topic."
}
