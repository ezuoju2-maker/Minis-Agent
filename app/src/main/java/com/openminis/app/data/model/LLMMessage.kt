package com.openminis.app.data.model

data class LLMMessage(
    val role: Role,
    val content: String,
    val imageParts: List<ImagePart> = emptyList(),
    val audioParts: List<AudioPart> = emptyList(),
    val contentParts: List<AgentContentPart> = emptyList(),
    val dbMessageId: String? = null,
    val reasoningContent: String? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }

    data class ImagePart(
        val data: ByteArray,
        val mimeType: String,
        val linuxPath: String? = null,
        val noVisionPlaceholder: String? = null,
    )

    data class AudioPart(
        val format: String,
        val base64Data: String,
    )
}

data class LLMResponse(
    val text: String,
    val stopReason: String?,
    val usage: LLMUsage?,
    val mediaAttachments: List<LLMMediaAttachment> = emptyList(),
)

data class LLMMediaAttachment(
    val type: MediaType,
    val mimeType: String,
    val data: ByteArray,
) {
    enum class MediaType(val value: String) {
        IMAGE("image"),
        AUDIO("audio"),
        VIDEO("video"),
    }
}
