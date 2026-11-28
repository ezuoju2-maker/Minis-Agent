package com.openminis.app.provider.wire

object AssistantWire {

    fun strictBlankContent(endpoint: String, modelId: String): Boolean {
        val e = endpoint.lowercase()
        val m = modelId.lowercase()
        return e.contains("moonshot") || e.contains("kimi") ||
            m.startsWith("kimi") || m.startsWith("moonshot")
    }

    fun contentField(visible: String, hasToolCalls: Boolean, strictBlankContent: Boolean): String? =
        if (hasToolCalls && visible.isBlank() && strictBlankContent) null else visible
}
