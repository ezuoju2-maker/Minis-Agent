package com.openminis.app.provider.voice

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

open class VoiceProvider(
    val providerId: String,
    val baseURL: String,
    val apiKey: String? = null,
) {
    var customUserAgent: String? = null

    private val baseHttpClient: OkHttpClient by lazy {
        Companion.httpClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val custom = customUserAgent?.trim()?.takeIf { it.isNotEmpty() }
            val ua = custom ?: if (request.header("User-Agent") == null) {
                com.openminis.app.provider.MinisUserAgent.DEFAULT
            } else {
                null
            }
            chain.proceed(if (ua == null) request else request.newBuilder().header("User-Agent", ua).build())
        }.build()
    }

    val httpClient: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseHttpClient)

    companion object {
        private const val TAG = "VoiceProvider"

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        fun iso6391(tag: String?): String? {
            val trimmed = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return trimmed.split('-', '_').firstOrNull()?.lowercase(Locale.ROOT)
        }

        fun wrapPcm16InWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val header = java.nio.ByteBuffer.allocate(44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            return header.array() + pcm
        }
    }


    open suspend fun transcribe(request: VoiceInputRequest): VoiceInputResponse {
        val model = request.resolvedModel
        if (model != null && usesChatBasedASR(model)) {
            Log.i(TAG, "Using chat-based ASR for ${model.displayName}")
            return transcribeChatBased(request)
        }
        if (!supportsVoiceInput) {
            throw VoiceProviderException.Unsupported("${javaClass.simpleName} does not support voice input")
        }
        val req = buildVoiceInputRequest(request)
        val data = executeRequest(req)
        return parseVoiceInputResponse(data, request)
    }

    open suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        if (!supportsVoiceOutput) {
            throw VoiceProviderException.Unsupported("${javaClass.simpleName} does not support voice output")
        }
        val req = buildVoiceOutputRequest(request)
        return executeRequest(req)
    }


    open val supportsVoiceInput: Boolean get() = true
    open val supportsVoiceOutput: Boolean get() = true


    open fun voiceInputEndpointPath(): String = "/v1/audio/transcriptions"
    open fun voiceOutputEndpointPath(): String = "/v1/audio/speech"


    open fun defaultVoiceInputModel(): String = "whisper-1"
    open fun defaultVoiceOutputModel(): String = "tts-1"
    open fun defaultVoiceOutputVoice(): String = "alloy"


    open fun buildVoiceInputRequest(request: VoiceInputRequest): Request {
        val url = composedUrlString(voiceInputEndpointPath())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "voice.wav",
                request.audioData.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", request.model ?: defaultVoiceInputModel())
        iso6391(request.language)?.let { multipart.addFormDataPart("language", it) }
        multipart.addFormDataPart("response_format", request.responseFormat.wireValue)
        request.prompt?.let { multipart.addFormDataPart("prompt", it) }

        val builder = Request.Builder().url(url).post(multipart.build())
        applyVoiceAuth(builder)
        return builder.build()
    }

    open fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val url = composedUrlString(voiceOutputEndpointPath())
        val body = JSONObject().apply {
            put("model", request.model ?: defaultVoiceOutputModel())
            put("input", request.input)
            put("voice", request.voice ?: defaultVoiceOutputVoice())
            put("response_format", request.responseFormat.wireValue)
            request.speed?.let { put("speed", it.toDouble()) }
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)
        return builder.build()
    }

    open fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val text = String(data, Charsets.UTF_8)
        runCatching {
            val obj = JSONObject(text)
            if (obj.has("text")) {
                return VoiceInputResponse(
                    text = obj.getString("text"),
                    language = obj.optString("language").takeIf { it.isNotBlank() },
                    durationSeconds = if (obj.has("duration")) obj.optDouble("duration") else null,
                )
            }
        }
        if (text.isNotEmpty()) return VoiceInputResponse(text = text)
        throw VoiceProviderException.Parse("Empty or undecodable ASR response")
    }

    open fun applyVoiceAuth(builder: Request.Builder) {
        val key = apiKey?.takeIf { it.isNotEmpty() } ?: return
        builder.header("Authorization", "Bearer $key")
    }


    open fun effectiveBaseURL(): String = baseURL.trimEnd('/')

    fun composedUrlString(path: String): String {
        val base = effectiveBaseURL()
        var p = path
        for (version in listOf("/v1", "/v2", "/v3")) {
            if (base.endsWith(version) && p.startsWith("$version/")) {
                p = p.removePrefix(version)
                break
            }
        }
        return base + p
    }

    open fun isAuthFailure(code: Int, body: ByteArray?): Boolean = code == 401 || code == 403

    suspend fun executeRequest(request: Request): ByteArray = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.bytes()
            if (!response.isSuccessful) {
                if (isAuthFailure(response.code, body)) {
                    Log.e(TAG, "Voice auth failed: HTTP ${response.code}")
                    throw VoiceProviderException.Auth()
                }
                Log.e(TAG, "Voice request failed: HTTP ${response.code}")
                throw VoiceProviderException.Http(response.code, body)
            }
            body ?: ByteArray(0)
        }
    }


    open fun usesChatBasedASR(model: com.openminis.app.data.model.LLMModel): Boolean {
        val id = model.id.lowercase()
        if (id.contains("omni")) return true
        if (!id.contains("audio")) return false
        return id.contains("gpt") || id.contains("qwen")
    }

    open suspend fun transcribeChatBased(request: VoiceInputRequest): VoiceInputResponse {
        val url = composedUrlString("/v1/chat/completions")
        val audioBase64 = Base64.encodeToString(request.audioData, Base64.NO_WRAP)
        val langHint = iso6391(request.language) ?: "auto"

        val body = JSONObject().apply {
            put("model", request.model ?: "gpt-4o-mini-audio-preview")
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "You are a speech-to-text transcription engine. Output ONLY the exact verbatim transcription of the audio. No commentary, no punctuation corrections, no translations, no markdown, no extra text. If the audio is empty or unintelligible, output an empty string. Language hint: $langHint.",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject()
                                                .put("data", audioBase64)
                                                .put("format", "wav"),
                                        ),
                                ),
                            ),
                    ),
            )
            put("max_tokens", 4096)
            put("temperature", 0)
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)

        val data = executeRequest(builder.build())
        val obj = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceProviderException.Parse("Undecodable chat ASR response")
        val text = obj.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?: ""
        return VoiceInputResponse(text = text)
    }
}
