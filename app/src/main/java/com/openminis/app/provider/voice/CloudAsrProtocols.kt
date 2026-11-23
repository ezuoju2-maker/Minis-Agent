package com.openminis.app.provider.voice

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object CloudAsrProtocols {


    const val PCM_RATE = 16_000

    fun pcm16kMono(wav: ByteArray): ByteArray? {
        if (wav.size < 12 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val len = buf.getInt(pos + 4)
            val body = pos + 8
            if (len < 0) return null
            if (id == "fmt " && body + 16 <= wav.size) {
                format = buf.getShort(body).toInt() and 0xFFFF
                channels = buf.getShort(body + 2).toInt() and 0xFFFF
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt() and 0xFFFF
                if (format == 0xFFFE && len >= 26 && body + 26 <= wav.size) {
                    format = buf.getShort(body + 24).toInt() and 0xFFFF
                }
            } else if (id == "data") {
                dataStart = body
                dataLen = minOf(len.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE, (wav.size - body).toLong()).toInt()
                break
            }
            pos = body + len + (len and 1)
        }
        val isInt16 = format == 1 && bits == 16
        val isFloat32 = format == 3 && bits == 32
        if (dataStart < 0 || channels <= 0 || rate <= 0 || !(isInt16 || isFloat32)) return null

        val frameBytes = channels * bits / 8
        val frames = dataLen / frameBytes
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val at = dataStart + f * frameBytes + c * bits / 8
                sum += if (isInt16) buf.getShort(at) / 32768f else buf.getFloat(at)
            }
            mono[f] = sum / channels
        }
        val outFrames = if (rate == PCM_RATE) frames else (frames.toLong() * PCM_RATE / rate).toInt()
        val out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        val step = rate.toDouble() / PCM_RATE
        for (i in 0 until outFrames) {
            val x = i * step
            val i0 = x.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val v = mono[i0] + (mono[i1] - mono[i0]) * (x - i0).toFloat()
            out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return out.array()
    }


    const val XUNFEI_SEGMENT_BYTES = 55 * PCM_RATE * 2

    const val XUNFEI_CHUNK_BYTES = 2 * 1280
    const val XUNFEI_CHUNK_INTERVAL_MS = 40L

    fun xunfeiLanguage(tag: String?): String =
        if (VoiceProvider.iso6391(tag) == "en") "en_us" else "zh_cn"

    fun xunfeiSegments(pcm: ByteArray): List<ByteArray> =
        if (pcm.isEmpty()) emptyList()
        else (pcm.indices step XUNFEI_SEGMENT_BYTES).map { pcm.copyOfRange(it, minOf(it + XUNFEI_SEGMENT_BYTES, pcm.size)) }

    fun xunfeiFrames(pcm: ByteArray, appId: String, language: String): List<String> {
        val enc = Base64.getEncoder()
        val chunks = if (pcm.isEmpty()) listOf(ByteArray(0))
        else (pcm.indices step XUNFEI_CHUNK_BYTES).map { pcm.copyOfRange(it, minOf(it + XUNFEI_CHUNK_BYTES, pcm.size)) }
        val out = ArrayList<String>(chunks.size + 1)
        chunks.forEachIndexed { i, chunk ->
            val data = JSONObject()
                .put("status", if (i == 0) 0 else 1)
                .put("format", "audio/L16;rate=16000")
                .put("encoding", "raw")
                .put("audio", enc.encodeToString(chunk))
            val msg = JSONObject()
            if (i == 0) {
                msg.put("common", JSONObject().put("app_id", appId))
                msg.put(
                    "business",
                    JSONObject()
                        .put("language", language)
                        .put("domain", "iat")
                        .put("accent", "mandarin")
                        .put("eos", 10_000),
                )
            }
            out += msg.put("data", data).toString()
        }
        out += JSONObject().put("data", JSONObject().put("status", 2)).toString()
        return out
    }

    class XunfeiResult {
        private val pieces = sortedMapOf<Int, String>()

        class Failure(val code: Int, message: String) : Exception("iFlytek error $code: $message")

        fun accept(message: String): Boolean {
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return false
            val code = json.optInt("code", 0)
            if (code != 0) throw Failure(code, json.optString("message"))
            val data = json.optJSONObject("data") ?: return false
            data.optJSONObject("result")?.let { result ->
                val sn = result.optInt("sn", pieces.size + 1)
                if (result.optString("pgs") == "rpl") {
                    val rg = result.optJSONArray("rg")
                    if (rg != null && rg.length() == 2) {
                        for (k in rg.optInt(0)..rg.optInt(1)) pieces.remove(k)
                    }
                }
                pieces[sn] = words(result.optJSONArray("ws"))
            }
            return data.optInt("status", 0) == 2
        }

        val text: String get() = pieces.values.joinToString("")

        private fun words(ws: JSONArray?): String {
            ws ?: return ""
            val sb = StringBuilder()
            for (i in 0 until ws.length()) {
                ws.optJSONObject(i)?.optJSONArray("cw")?.optJSONObject(0)?.optString("w")?.let(sb::append)
            }
            return sb.toString()
        }
    }


    const val BAILIAN_ASR_MODEL = "qwen3-asr-flash"

    fun bailianModel(requested: String?): String {
        val id = requested?.trim().orEmpty()
        return if (id.lowercase().startsWith("qwen") && id.lowercase().contains("asr")) id else BAILIAN_ASR_MODEL
    }

    fun bailianBody(model: String, wav: ByteArray, language: String?): JSONObject {
        val audio = JSONObject().put(
            "data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav),
        )
        val user = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "input_audio").put("input_audio", audio)))
        val options = JSONObject().put("enable_itn", false)
        VoiceProvider.iso6391(language)?.let { options.put("language", it) }
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(user))
            .put("stream", false)
            .put("asr_options", options)
    }

    fun bailianParse(body: String): Pair<String, Double?>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        if (!message.has("content")) return null
        val seconds = json.optJSONObject("usage")?.optDouble("seconds")?.takeIf { !it.isNaN() }
        return message.optString("content") to seconds
    }


    const val DOUBAO_FLASH_RESOURCE = "volc.bigasr.auc_turbo"

    fun doubaoResource(model: String?): String =
        model?.trim()?.takeIf { it.startsWith("volc.") } ?: DOUBAO_FLASH_RESOURCE

    fun doubaoBody(wav: ByteArray): JSONObject = JSONObject()
        .put("user", JSONObject().put("uid", "minis_user"))
        .put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(wav)))
        .put("request", JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true))

    fun doubaoFailure(statusCode: String?, message: String?, httpCode: Int, resource: String): String {
        val code = statusCode?.takeIf { it.isNotBlank() }
        val detail = message?.takeIf { it.isNotBlank() }
        val head = "Doubao ASR failed" + (code?.let { " ($it)" } ?: " (HTTP $httpCode)") + (detail?.let { ": $it" } ?: "")
        return when {
            code == "45000010" || httpCode == 401 -> "$head. Check the API Key (Volcengine new console)."
            httpCode == 403 || detail?.contains("resource", ignoreCase = true) == true ||
                detail?.contains("grant", ignoreCase = true) == true ->
                "$head. The account may not have the \"$resource\" resource enabled: enable recording file " +
                    "recognition (flash) in the Volcengine console, or enter the resource id you were granted as the model."
            code == "20000003" -> "$head. No speech was detected."
            else -> head
        }
    }
}
