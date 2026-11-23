package com.openminis.app.provider.voice

import android.util.Base64
import android.util.Log
import com.openminis.app.data.model.hasAudioOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException



class GroqVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {
    override val supportsVoiceOutput: Boolean get() = false
    override fun defaultVoiceInputModel() = "whisper-large-v3-turbo"
}


class AlibabaVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {
    override fun defaultVoiceInputModel() = CloudAsrProtocols.BAILIAN_ASR_MODEL

    override suspend fun transcribe(request: VoiceInputRequest): VoiceInputResponse {
        val model = CloudAsrProtocols.bailianModel(request.model)
        val body = CloudAsrProtocols.bailianBody(model, request.audioData, request.language)
        val builder = Request.Builder()
            .url(composedUrlString("/v1/chat/completions"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)
        val data = executeRequest(builder.build())
        val (text, seconds) = CloudAsrProtocols.bailianParse(String(data, Charsets.UTF_8))
            ?: throw VoiceProviderException.Parse("Unexpected Bailian ASR response format")
        return VoiceInputResponse(text = text, language = request.language, durationSeconds = seconds)
    }
    override fun defaultVoiceOutputModel() = "cosyvoice-v2"
    override fun defaultVoiceOutputVoice() = "longxiaochun"
}


class ZhipuVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    override fun effectiveBaseURL(): String {
        val url = super.effectiveBaseURL().toHttpUrlOrNull() ?: return DEFAULT_ORIGIN
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port"
    }

    override fun voiceOutputEndpointPath() = "/api/paas/v4/audio/speech"
    override fun voiceInputEndpointPath() = "/api/paas/v4/audio/transcriptions"
    override fun defaultVoiceOutputModel() = "cogtts"
    override fun defaultVoiceOutputVoice() = "tongtong"
    override fun defaultVoiceInputModel() = "glm-asr"

    override fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val voice = request.voice
            ?.takeIf { it.isNotBlank() && it != request.model }
            ?: defaultVoiceOutputVoice()
        return super.buildVoiceOutputRequest(
            request.copy(voice = voice, responseFormat = VoiceOutputFormat.WAV),
        )
    }

    companion object {
        const val DEFAULT_ORIGIN = "https://open.bigmodel.cn"

        fun matches(normalizedBase: String): Boolean =
            normalizedBase.contains("open.bigmodel.cn") ||
                normalizedBase.contains("api.z.ai") ||
                normalizedBase.contains("zhipu")
    }
}


class XAIVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {
    override fun voiceInputEndpointPath() = "/v1/stt"
    override fun defaultVoiceInputModel() = "grok-stt"
    override fun defaultVoiceOutputModel() = "grok-tts-1"
    override fun defaultVoiceOutputVoice() = "eve"
}


class MiniMaxVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    override val supportsVoiceInput: Boolean get() = false

    override fun effectiveBaseURL(): String {
        var base = super.effectiveBaseURL().trimEnd('/')
        for (suffix in listOf("/v1", "/anthropic")) {
            if (base.endsWith(suffix)) base = base.dropLast(suffix.length)
        }
        return base.trimEnd('/')
    }

    override fun voiceOutputEndpointPath() = "/v1/t2a_v2"
    override fun defaultVoiceOutputModel() = "speech-2.8-hd"
    override fun defaultVoiceOutputVoice() = "female-shaonv"

    override fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val url = composedUrlString(voiceOutputEndpointPath())
        val speedInt = ((request.speed ?: 1.0f) * 100).toInt()
        val requestedVoice = if (request.voice == request.model) null else request.voice
        val body = JSONObject().apply {
            put("model", request.model ?: defaultVoiceOutputModel())
            put("text", request.input)
            put("stream", false)
            put(
                "voice_setting",
                JSONObject()
                    .put("voice_id", requestedVoice ?: defaultVoiceOutputVoice())
                    .put("speed", speedInt)
                    .put("vol", 100)
                    .put("pitch", 0),
            )
            put(
                "audio_setting",
                JSONObject()
                    .put("sample_rate", 32000)
                    .put("bitrate", 128000)
                    .put("format", request.responseFormat.wireValue),
            )
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)
        return builder.build()
    }

    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val raw = executeRequest(buildVoiceOutputRequest(request))
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
        json?.optJSONObject("base_resp")?.let { base ->
            val code = base.optInt("status_code", 0)
            if (code != 0) {
                val msg = base.optString("status_msg").ifBlank { "unknown error" }
                throw VoiceProviderException.Parse("MiniMax TTS error [$code]: $msg")
            }
        }
        json?.optJSONObject("data")?.optString("audio")?.takeIf { it.isNotEmpty() }?.let { enc ->
            decodeHex(enc)?.let { return it }
            runCatching { Base64.decode(enc, Base64.DEFAULT) }.getOrNull()?.let { return it }
            throw VoiceProviderException.Parse("MiniMax TTS: data.audio is neither hex nor base64")
        }
        val b64 = json?.optJSONObject("audio")?.optString("audio")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected MiniMax TTS response format")
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            ?: throw VoiceProviderException.Parse("Unexpected MiniMax TTS response format")
    }

    private fun decodeHex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.isEmpty()) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}


class DoubaoVoiceProvider(providerId: String, apiKey: String?) :
    VoiceProvider(providerId, "https://openspeech.bytedance.com", apiKey) {

    override fun applyVoiceAuth(builder: Request.Builder) {
        val key = apiKey?.takeIf { it.isNotEmpty() } ?: return
        builder.header("X-Api-Key", key)
    }


    override fun voiceOutputEndpointPath() = "/api/v3/tts/unidirectional"
    override fun defaultVoiceInputModel() = "bigmodel"
    override fun defaultVoiceOutputModel() = "zh_female_cancan_uranus_bigtts"
    override fun defaultVoiceOutputVoice() = "zh_female_cancan_uranus_bigtts"

    override fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val url = composedUrlString(voiceOutputEndpointPath())
        val raw = request.model ?: request.voice ?: defaultVoiceOutputVoice()
        val speaker = if (raw.startsWith("seed-tts-")) defaultVoiceOutputVoice() else raw
        val resourceId = if (speaker.contains("_uranus_") || speaker.startsWith("saturn_")) {
            "seed-tts-2.0"
        } else {
            "seed-tts-1.0"
        }
        val body = JSONObject().put(
            "req_params",
            JSONObject()
                .put("text", request.input)
                .put("speaker", speaker)
                .put(
                    "audio_params",
                    JSONObject()
                        .put("format", if (request.responseFormat == VoiceOutputFormat.WAV) "wav" else "mp3")
                        .put("sample_rate", 24000),
                ),
        )
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("X-Api-Resource-Id", resourceId)
            .header("Connection", "keep-alive")
        applyVoiceAuth(builder)
        return builder.build()
    }

    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val raw = executeRequest(buildVoiceOutputRequest(request))
        val out = ByteArrayOutputStream()
        for (line in String(raw, Charsets.UTF_8).split('\n')) {
            val frame = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (frame.optInt("code", -1) != 0) continue
            val b64 = frame.optString("data").takeIf { it.isNotEmpty() } ?: continue
            val chunk = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            out.write(chunk)
        }
        val audio = out.toByteArray()
        if (audio.isEmpty()) {
            val preview = String(raw.copyOfRange(0, minOf(raw.size, 500)), Charsets.UTF_8)
            throw VoiceProviderException.Parse("Doubao v3 TTS: no audio frames in response. Raw: $preview")
        }
        return audio
    }


    override fun voiceInputEndpointPath() = "/api/v3/auc/bigmodel/recognize/flash"

    override fun buildVoiceInputRequest(request: VoiceInputRequest): Request {
        val builder = Request.Builder()
            .url(composedUrlString(voiceInputEndpointPath()))
            .post(CloudAsrProtocols.doubaoBody(request.audioData).toString().toRequestBody("application/json".toMediaType()))
            .header("X-Api-Resource-Id", CloudAsrProtocols.doubaoResource(request.model))
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Sequence", "-1")
        applyVoiceAuth(builder)
        return builder.build()
    }

    override suspend fun transcribe(request: VoiceInputRequest): VoiceInputResponse {
        val req = buildVoiceInputRequest(request)
        val resource = req.header("X-Api-Resource-Id").orEmpty()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(req).execute().use { response ->
                val status = response.header("X-Api-Status-Code")
                val message = response.header("X-Api-Message")
                val body = response.body?.bytes() ?: ByteArray(0)
                if (status == "20000003") {
                    return@withContext VoiceInputResponse(text = "", language = request.language)
                }
                if (!response.isSuccessful || (status != null && status != "20000000")) {
                    Log.e("DoubaoASR", "flash failed: HTTP ${response.code} status=$status message=$message")
                    if (response.code == 401 || status == "45000010") throw VoiceProviderException.Auth()
                    throw VoiceProviderException.Service(
                        CloudAsrProtocols.doubaoFailure(status, message, response.code, resource),
                    )
                }
                parseVoiceInputResponse(body, request)
            }
        }
    }

    override fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val text = json?.optJSONObject("result")?.optString("text")
            ?: throw VoiceProviderException.Parse("Unexpected Doubao v3 ASR response format")
        val durationMs = json.optJSONObject("audio_info")?.optDouble("duration")
            ?.takeIf { !it.isNaN() }
        return VoiceInputResponse(
            text = text,
            language = request.language,
            durationSeconds = durationMs?.div(1000),
        )
    }
}


class XunfeiVoiceProvider(
    providerId: String,
    private val appId: String,
    apiKey: String,
    private val apiSecret: String,
) : VoiceProvider(providerId, "https://iat-api.xfyun.cn", apiKey) {

    companion object {
        private const val DEFAULT_TTS_VOICE = "xiaoyan"
    }

    override fun applyVoiceAuth(builder: Request.Builder) {}

    override suspend fun transcribe(request: VoiceInputRequest): VoiceInputResponse {
        val pcm = CloudAsrProtocols.pcm16kMono(request.audioData)
            ?: throw VoiceProviderException.Parse("iFlytek dictation needs WAV (PCM) audio")
        val language = CloudAsrProtocols.xunfeiLanguage(request.language)
        val text = StringBuilder()
        for (segment in CloudAsrProtocols.xunfeiSegments(pcm)) {
            text.append(dictate(CloudAsrProtocols.xunfeiFrames(segment, appId, language)))
        }
        return VoiceInputResponse(
            text = text.toString(),
            language = request.language,
            durationSeconds = pcm.size / (2.0 * CloudAsrProtocols.PCM_RATE),
        )
    }

    private suspend fun dictate(frames: List<String>): String {
        val url = buildSignedURL(host = "iat-api.xfyun.cn", path = "/v2/iat", date = Date())
            .replaceFirst("https://", "wss://")
        val result = CloudAsrProtocols.XunfeiResult()
        val done = kotlinx.coroutines.CompletableDeferred<String>()
        val socket = httpClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        if (result.accept(text)) {
                            webSocket.close(1000, null)
                            done.complete(result.text)
                        }
                    } catch (e: CloudAsrProtocols.XunfeiResult.Failure) {
                        webSocket.cancel()
                        done.completeExceptionally(
                            if (e.code == 10005 || e.code == 11200 || e.code == 10313) {
                                VoiceProviderException.Service("${e.message} (check the App ID, API Key and API Secret, and that dictation is enabled)")
                            } else {
                                VoiceProviderException.Service(e.message ?: "iFlytek error ${e.code}")
                            },
                        )
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    done.complete(result.text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    done.completeExceptionally(
                        if (code == 401 || code == 403) VoiceProviderException.Auth() else t,
                    )
                }
            },
        )
        try {
            for (frame in frames) {
                if (done.isCompleted) break
                socket.send(frame)
                kotlinx.coroutines.delay(CloudAsrProtocols.XUNFEI_CHUNK_INTERVAL_MS)
            }
            return kotlinx.coroutines.withTimeout(30_000) { done.await() }
        } finally {
            socket.cancel()
        }
    }

    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val signedUrl = buildSignedURL(host = "tts-api.xfyun.cn", path = "/v2/tts", date = Date())
            .replaceFirst("https://", "wss://")
        val voice = request.voice?.takeIf { it.isNotEmpty() } ?: DEFAULT_TTS_VOICE
        val textB64 = Base64.encodeToString(request.input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payload = JSONObject()
            .put("common", JSONObject().put("app_id", appId))
            .put(
                "business",
                JSONObject()
                    .put("aue", "raw")
                    .put("auf", "audio/L16;rate=16000")
                    .put("vcn", voice)
                    .put("tte", "UTF8"),
            )
            .put("data", JSONObject().put("status", 2).put("text", textB64))
            .toString()

        return suspendCancellableCoroutine { cont ->
            val pcm = ByteArrayOutputStream()
            val wsRequest = Request.Builder().url(signedUrl).build()
            val socket = httpClient.newWebSocket(
                wsRequest,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(payload)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val code = json.optInt("code", 0)
                        if (code != 0) {
                            webSocket.cancel()
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    VoiceProviderException.Parse(
                                        "Xunfei TTS error code $code: ${json.optString("message")}",
                                    ),
                                )
                            }
                            return
                        }
                        val dataObj = json.optJSONObject("data") ?: return
                        dataObj.optString("audio").takeIf { it.isNotEmpty() }?.let { b64 ->
                            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                                ?.let(pcm::write)
                        }
                        if (dataObj.optInt("status", 0) == 2) {
                            webSocket.close(1000, null)
                            if (cont.isActive) {
                                val bytes = pcm.toByteArray()
                                if (bytes.isEmpty()) {
                                    cont.resumeWithException(VoiceProviderException.Parse("Xunfei TTS empty audio"))
                                } else {
                                    cont.resume(wrapPcm16InWav(bytes, sampleRate = 16000))
                                }
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                },
            )
            cont.invokeOnCancellation { socket.cancel() }
        }
    }


    private fun buildSignedURL(host: String, path: String, date: Date): String {
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val dateStr = formatter.format(date)
        val signatureOrigin = "host: $host\ndate: $dateStr\nGET $path HTTP/1.1"
        val signatureB64 = hmacSha256Base64(signatureOrigin, apiSecret)
        val authOrigin = "api_key=\"${apiKey ?: ""}\", " +
            "algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", " +
            "signature=\"$signatureB64\""
        val authB64 = Base64.encodeToString(authOrigin.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val encodedDate = URLEncoder.encode(dateStr, "UTF-8")
        return "https://$host$path?authorization=$authB64&date=$encodedDate&host=$host"
    }

    private fun hmacSha256Base64(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}


class GeminiVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    companion object {
        private val KNOWN_VOICES = setOf("Kore", "Puck", "Charon", "Fenrir", "Aoede", "Zephyr", "Leda", "Orus")
        private fun geminiVoice(requested: String?): String {
            val v = requested?.takeIf { it.isNotEmpty() } ?: return "Kore"
            return if (v in KNOWN_VOICES) v else "Kore"
        }

        private fun sampleRate(fromMime: String): Int {
            val idx = fromMime.indexOf("rate=")
            if (idx < 0) return 24000
            return fromMime.substring(idx + 5).takeWhile { it.isDigit() }.toIntOrNull() ?: 24000
        }
    }

    override val supportsVoiceInput: Boolean get() = false
    override fun defaultVoiceOutputModel() = "gemini-2.5-flash-preview-tts"

    override fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val model = request.model ?: defaultVoiceOutputModel()
        var base = effectiveBaseURL()
        if (!base.contains("/v1beta")) base = base.trimEnd('/') + "/v1beta"
        val url = "$base/models/$model:generateContent?key=${apiKey ?: ""}"
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.input))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", geminiVoice(request.voice)),
                            ),
                        ),
                    ),
            )
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val raw = executeRequest(buildVoiceOutputRequest(request))
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceProviderException.Parse("Unexpected Gemini TTS response")
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw VoiceProviderException.Parse("Unexpected Gemini TTS response")
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val b64 = inline.optString("data").takeIf { it.isNotEmpty() } ?: continue
            val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            val mime = inline.optString("mimeType").ifBlank { "audio/L16;rate=24000" }
            if (mime.lowercase().contains("wav")) return pcm
            return wrapPcm16InWav(pcm, sampleRate(mime))
        }
        throw VoiceProviderException.Parse("No audio in Gemini TTS response")
    }
}


class ElevenLabsVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    companion object {
        private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
        private const val MODEL_ID = "eleven_multilingual_v2"
    }

    override val supportsVoiceInput: Boolean get() = false

    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val voiceId = request.model?.takeIf { it.isNotEmpty() }
            ?: request.voice?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_VOICE_ID
        var base = effectiveBaseURL()
        if (!base.contains("/v1")) base += "/v1"
        val body = JSONObject()
            .put("text", request.input)
            .put("model_id", MODEL_ID)
            .put(
                "voice_settings",
                JSONObject().put("stability", 0.5).put("similarity_boost", 0.75),
            )
        val req = Request.Builder()
            .url("$base/text-to-speech/$voiceId")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("xi-api-key", apiKey ?: "")
            .header("Accept", "audio/mpeg")
            .build()
        return executeRequest(req)
    }
}


class DeepgramVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    companion object {
        private const val DEFAULT_ASR_MODEL = "nova-2"
        private const val DEFAULT_TTS_MODEL = "aura-asteria-en"
    }

    private fun dgBase(): String {
        var base = effectiveBaseURL()
        if (!base.contains("/v1")) base += "/v1"
        return base
    }

    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val model = request.model?.takeIf { it.isNotEmpty() } ?: DEFAULT_TTS_MODEL
        val req = Request.Builder()
            .url("${dgBase()}/speak?model=$model")
            .post(
                JSONObject().put("text", request.input).toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            .header("Authorization", "Token ${apiKey ?: ""}")
            .build()
        return executeRequest(req)
    }

    override fun buildVoiceInputRequest(request: VoiceInputRequest): Request {
        val model = request.model?.takeIf { it.isNotEmpty() } ?: DEFAULT_ASR_MODEL
        var qs = "model=$model&smart_format=true"
        request.language?.takeIf { it.isNotEmpty() }?.let { qs += "&language=$it" }
        return Request.Builder()
            .url("${dgBase()}/listen?$qs")
            .post(request.audioData.toRequestBody("audio/wav".toMediaType()))
            .header("Authorization", "Token ${apiKey ?: ""}")
            .build()
    }

    override fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val transcript = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?.optJSONObject("results")
            ?.optJSONArray("channels")
            ?.optJSONObject(0)
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?.optString("transcript")
            ?: throw VoiceProviderException.Parse("Unexpected Deepgram ASR response")
        return VoiceInputResponse(text = transcript, language = request.language)
    }
}


class AzureTTSVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    override val supportsVoiceInput: Boolean get() = false
    override fun defaultVoiceOutputModel() = "azure-tts"
    override fun defaultVoiceOutputVoice() = "zh-CN-XiaoxiaoNeural"

    override fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val url = composedUrlString("/cognitiveservices/v1")
        val voice = request.voice ?: defaultVoiceOutputVoice()
        val parts = voice.split("-")
        val lang = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "en-US"
        val escaped = request.input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$lang\">" +
            "<voice name=\"$voice\">$escaped</voice></speak>"
        val builder = Request.Builder()
            .url(url)
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
        apiKey?.takeIf { it.isNotEmpty() }?.let { builder.header("Ocp-Apim-Subscription-Key", it) }
        return builder.build()
    }
}


class MimoVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    companion object {
        private const val TAG = "MimoVoice"
        private val API_MODELS = setOf("mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone")
    }

    override fun defaultVoiceInputModel() = "mimo-v2.5-asr"
    override fun defaultVoiceOutputModel() = "mimo-v2.5-tts"
    override fun defaultVoiceOutputVoice() = "mimo_default"


    override suspend fun transcribe(request: VoiceInputRequest): VoiceInputResponse {
        val url = composedUrlString("/v1/chat/completions")
        val audioBase64 = Base64.encodeToString(request.audioData, Base64.NO_WRAP)
        val lang = iso6391(request.language) ?: "auto"
        val body = JSONObject().apply {
            put("model", request.model ?: defaultVoiceInputModel())
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_audio")
                                    .put(
                                        "input_audio",
                                        JSONObject().put("data", audioBase64).put("format", "wav"),
                                    ),
                            ),
                        ),
                ),
            )
            put("asr_options", JSONObject().put("language", lang))
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)

        val data = executeRequest(builder.build())
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val text = json?.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: throw VoiceProviderException.Parse("Unexpected MiMo ASR response format")
        val seconds = json.optJSONObject("usage")?.optDouble("seconds")?.takeIf { !it.isNaN() }
        return VoiceInputResponse(text = text, durationSeconds = seconds)
    }


    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val url = composedUrlString("/v1/chat/completions")
        val rawModel = request.model ?: defaultVoiceOutputModel()
        val apiModel: String
        val voiceId: String?
        if (rawModel in API_MODELS) {
            apiModel = rawModel
            voiceId = request.voice
        } else {
            apiModel = "mimo-v2.5-tts"
            voiceId = rawModel
        }
        val audioParams = JSONObject().put("format", "wav")
        if (apiModel != "mimo-v2.5-tts-voicedesign" && voiceId != null) {
            audioParams.put("voice", voiceId)
        }
        val body = JSONObject().apply {
            put("model", apiModel)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", ""))
                    .put(JSONObject().put("role", "assistant").put("content", request.input)),
            )
            put("audio", audioParams)
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)

        val data = executeRequest(builder.build())
        val b64 = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optJSONObject("audio")
            ?.optString("data")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected MiMo TTS response format")
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            ?: throw VoiceProviderException.Parse("Unexpected MiMo TTS response format")
    }
}


class OpenRouterVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    enum class TtsRoute { SPEECH_ENDPOINT, CHAT_AUDIO, UNSUPPORTED }

    companion object {
        private const val PCM_SAMPLE_RATE = 24000

        private val KNOWN_VOICES = setOf(
            "alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral",
            "verse", "ballad", "ash", "sage", "marin", "cedar",
        )

        private val AUTH_FAILURE_PHRASES = listOf(
            "user not found", "no auth credentials", "invalid api key", "invalid_api_key",
        )

        fun isDedicatedTranscriptionModel(modelId: String): Boolean {
            val id = modelId.lowercase(Locale.ROOT)
            return id.contains("whisper") ||
                id.contains("transcribe") ||
                id.contains("deepgram/")
        }

        private fun looksLikeChatAudio(modelId: String): Boolean {
            val id = modelId.lowercase(Locale.ROOT)
            if (!id.contains("audio")) return false
            return id.contains("gpt") || id.contains("openai")
        }

        fun ttsRoute(modelId: String, model: com.openminis.app.data.model.LLMModel?): TtsRoute {
            if (modelId in com.openminis.app.data.model.VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS) {
                return TtsRoute.CHAT_AUDIO
            }
            when (model?.voiceRole) {
                com.openminis.app.data.model.VoiceRole.TTS -> return TtsRoute.SPEECH_ENDPOINT
                null -> Unit
                else -> return TtsRoute.UNSUPPORTED
            }
            if (looksLikeChatAudio(modelId)) return TtsRoute.CHAT_AUDIO
            val declaresAudioOut = model?.hasAudioOutput == true
            val nameSaysTts = com.openminis.app.data.model.VoiceModality
                .inferDedicatedVoiceModality(modelId, model?.displayName ?: modelId)
                ?.second == listOf("audio")
            return if (declaresAudioOut || nameSaysTts) TtsRoute.SPEECH_ENDPOINT else TtsRoute.UNSUPPORTED
        }

        fun routesAsrThroughChat(model: com.openminis.app.data.model.LLMModel): Boolean {
            if (model.id in com.openminis.app.data.model.VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS) return true
            model.voiceRole?.let { return it != com.openminis.app.data.model.VoiceRole.STT }
            return !isDedicatedTranscriptionModel(model.id)
        }

        fun speechVoice(requested: String?, modelId: String): String? =
            requested?.trim()?.takeIf { it.isNotEmpty() && it != modelId }

        fun isAuthFailureBody(code: Int, body: ByteArray?): Boolean {
            if (code != 401 && code != 403) return false
            val text = body?.toString(Charsets.UTF_8)?.lowercase(Locale.ROOT) ?: return false
            return AUTH_FAILURE_PHRASES.any { text.contains(it) }
        }
    }

    override fun isAuthFailure(code: Int, body: ByteArray?): Boolean = isAuthFailureBody(code, body)

    override fun applyVoiceAuth(builder: Request.Builder) {
        super.applyVoiceAuth(builder)
        builder.header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
        builder.header("X-Title", "Minis App")
    }


    override fun usesChatBasedASR(model: com.openminis.app.data.model.LLMModel): Boolean =
        routesAsrThroughChat(model)


    private fun resolvedVoice(requested: String?, modelId: String): String {
        val want = requested?.takeIf { it.isNotEmpty() } ?: return defaultVoiceOutputVoice()
        val lowered = want.lowercase(Locale.ROOT)
        if (KNOWN_VOICES.contains(lowered)) return lowered
        Log.i(
            "VoiceProvider",
            "OpenRouter chat-audio: ignoring non-voice '$want' for $modelId, using ${defaultVoiceOutputVoice()}",
        )
        return defaultVoiceOutputVoice()
    }

    override fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val modelId = request.model ?: defaultVoiceOutputModel()
        val body = JSONObject().apply {
            put("model", modelId)
            put("input", request.input)
            speechVoice(request.voice, modelId)?.let { put("voice", it) }
            put("response_format", request.responseFormat.wireValue)
            request.speed?.let { put("speed", it.toDouble()) }
        }
        val builder = Request.Builder()
            .url(composedUrlString(voiceOutputEndpointPath()))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)
        return builder.build()
    }

    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val modelId = request.model ?: defaultVoiceOutputModel()
        when (ttsRoute(modelId, request.resolvedModel)) {
            TtsRoute.SPEECH_ENDPOINT -> return executeRequest(buildVoiceOutputRequest(request))
            TtsRoute.UNSUPPORTED -> {
                Log.w("VoiceProvider", "OpenRouter TTS: $modelId is not a speech model (voiceRole=${request.resolvedModel?.voiceRole})")
                throw VoiceProviderException.Unsupported(
                    "$modelId does not support speech synthesis on OpenRouter. Pick a text-to-speech model.",
                )
            }
            TtsRoute.CHAT_AUDIO -> Unit
        }

        val url = composedUrlString("/v1/chat/completions")
        val body = JSONObject().apply {
            put("model", modelId)
            put("modalities", JSONArray().put("text").put("audio"))
            put(
                "audio",
                JSONObject()
                    .put("voice", resolvedVoice(request.voice, modelId))
                    .put("format", "pcm16"),
            )
            put("stream", true)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", request.input),
                ),
            )
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)

        val pcm = ByteArrayOutputStream()
        val transcript = StringBuilder()
        withContext(Dispatchers.IO) {
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.bytes()
                    if (isAuthFailure(response.code, errBody)) {
                        Log.e("VoiceProvider", "OpenRouter voice auth failed: HTTP ${response.code}")
                        throw VoiceProviderException.Auth()
                    }
                    Log.e(
                        "VoiceProvider",
                        "OpenRouter chat-audio failed: HTTP ${response.code} " +
                            "body=${errBody?.toString(Charsets.UTF_8).orEmpty()}",
                    )
                    throw VoiceProviderException.Http(response.code, errBody)
                }
                val source = response.body?.source()
                    ?: throw VoiceProviderException.Parse("OpenRouter returned an empty body")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val choices = obj.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val audio = choices.optJSONObject(i)
                            ?.optJSONObject("delta")
                            ?.optJSONObject("audio")
                            ?: continue
                        audio.optString("data").takeIf { it.isNotEmpty() }?.let { b64 ->
                            runCatching { Base64.decode(b64, Base64.DEFAULT) }
                                .getOrNull()?.let { pcm.write(it) }
                        }
                        audio.optString("transcript").takeIf { it.isNotEmpty() }
                            ?.let { transcript.append(it) }
                    }
                }
            }
        }

        val pcmBytes = pcm.toByteArray()
        if (pcmBytes.isEmpty()) {
            throw VoiceProviderException.Parse(
                "OpenRouter returned no audio data for $modelId" +
                    if (transcript.isEmpty()) "" else " (transcript: ${transcript.take(80)})",
            )
        }
        Log.i(
            "VoiceProvider",
            "OpenRouter chat-audio ok model=$modelId pcmBytes=${pcmBytes.size} " +
                "transcriptChars=${transcript.length}",
        )
        return wrapPcm16InWav(pcmBytes, PCM_SAMPLE_RATE)
    }
}
