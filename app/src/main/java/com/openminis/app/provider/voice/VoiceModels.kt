package com.openminis.app.provider.voice

import com.openminis.app.data.model.LLMModel
import org.json.JSONObject


data class VoiceInputRequest(
    val audioData: ByteArray,
    val model: String? = null,
    val language: String? = null,
    val responseFormat: VoiceInputFormat = VoiceInputFormat.JSON,
    val prompt: String? = null,
    val resolvedModel: LLMModel? = null,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

enum class VoiceInputFormat(val wireValue: String) {
    JSON("json"), TEXT("text"), SRT("srt"), VTT("vtt")
}

data class VoiceInputResponse(
    val text: String,
    val language: String? = null,
    val durationSeconds: Double? = null,
)

data class VoiceOutputRequest(
    val input: String,
    val model: String? = null,
    val voice: String? = null,
    val speed: Float? = null,
    val responseFormat: VoiceOutputFormat = VoiceOutputFormat.MP3,
    val resolvedModel: LLMModel? = null,
)

enum class VoiceOutputFormat(val wireValue: String) {
    MP3("mp3"), OPUS("opus"), WAV("wav"), AAC("aac")
}

sealed class VoiceProviderException(message: String) : Exception(message) {
    class Unsupported(detail: String) : VoiceProviderException("Unsupported: $detail")
    class Http(val code: Int, val body: ByteArray?) :
        VoiceProviderException(httpMessage(code, body))
    class Parse(detail: String) : VoiceProviderException("Parse failed: $detail")
    class Service(detail: String) : VoiceProviderException(detail)
    class Auth : VoiceProviderException("Authentication failed, please check the API key")
    class NoAudioData : VoiceProviderException("No audio data")

    companion object {
        private fun httpMessage(code: Int, body: ByteArray?): String {
            serverMessage(body)?.let { return "Request failed (HTTP $code): $it" }
            if (code == 404) {
                return "Request failed (HTTP 404) — check the provider's Base URL and model name"
            }
            return "Request failed (HTTP $code)"
        }

        fun serverMessage(body: ByteArray?): String? {
            if (body == null || body.isEmpty()) return null
            val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return null
            runCatching {
                val obj = JSONObject(text)
                (obj.optJSONObject("error")?.optString("message"))?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                obj.optString("error").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("message").takeIf { it.isNotBlank() }?.let { return it }
            }
            val trimmed = text.trim()
            return trimmed.takeIf { it.isNotEmpty() && it.length < 200 }
        }
    }
}
