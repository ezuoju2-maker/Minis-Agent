package com.openminis.app.terminal

object MinisUrlMarker {
    private val PATTERN = Regex("\u001B]1337;MinisOpenURL=([^\u0007\u001B]*)(?:\u0007|\u001B\\\\)")

    fun extract(text: String): Pair<String, List<String>> {
        if (!text.contains("MinisOpenURL=")) return text to emptyList()
        val matches = PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()

        val urls = matches.map { it.groupValues[1] }.filter { it.isNotEmpty() }
        val cleaned = PATTERN.replace(text, "")
        return cleaned to urls
    }

    fun stripMarkers(text: String): String {
        if (!text.contains("MinisOpenURL=")) return text
        return PATTERN.replace(text, "")
    }

    fun extractUrl(text: String): String? {
        if (!text.contains("MinisOpenURL=")) return null
        return PATTERN.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }
}
