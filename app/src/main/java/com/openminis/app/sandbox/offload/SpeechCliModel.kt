package com.openminis.app.sandbox.offload

import android.speech.SpeechRecognizer
import com.openminis.app.speech.RecognizerErrorCodes
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object SpeechCli {
    const val TOOL = "android-speech"

    const val EXIT_OK = 0
    const val EXIT_ERROR = 1
    const val EXIT_INVALID_ARGS = 2
    const val EXIT_NOT_AVAILABLE = 4
    const val EXIT_PERMISSION = 77

    const val EXIT_CANCELLED = 130


    fun ok(action: String, data: JSONObject, now: Date = Date()): JSONObject {
        val out = JSONObject()
            .put("ok", true)
            .put("tool", TOOL)
            .put("action", action)
            .put("data", data)
            .put("timestamp", iso(now))
        for (key in data.keys()) {
            if (!out.has(key)) out.put(key, data.get(key))
        }
        return out
    }

    fun error(
        action: String,
        code: String,
        message: String,
        extra: JSONObject? = null,
        now: Date = Date(),
    ): JSONObject {
        val err = JSONObject().put("code", code).put("message", message)
        if (extra != null) for (key in extra.keys()) err.put(key, extra.get(key))
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL)
            .put("action", action)
            .put("error", err)
            .put("message", message)
            .put("timestamp", iso(now))
    }

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(date)


    data class Environment(
        val services: List<String>,
        val defaultService: String?,
        val onDeviceAvailable: Boolean?,
        val locale: String,
        val hasMicPermission: Boolean,
        val sdkInt: Int,
        val lastError: LastError? = null,
    )

    data class LastError(val code: Int, val atMillis: Long, val recognizer: String?)

    fun defaultVisible(env: Environment): Boolean {
        val def = env.defaultService?.trim().orEmpty()
        if (def.isEmpty()) return false
        val norm = normalizeComponent(def)
        return env.services.any { normalizeComponent(it) == norm }
    }

    fun normalizeComponent(c: String): String {
        val slash = c.indexOf('/')
        if (slash <= 0) return c
        val pkg = c.substring(0, slash)
        val cls = c.substring(slash + 1)
        return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
    }

    fun assess(env: Environment): Pair<Boolean, String?> {
        if (env.services.isEmpty()) {
            return false to "No speech recognition service is installed or visible to Minis, " +
                "so the system recognizer cannot work on this device."
        }
        val def = env.defaultService?.trim().orEmpty()
        if (def.isEmpty()) {
            return false to "No default voice recognition service is set " +
                "(Settings.Secure voice_recognition_service is empty), so Android rejects " +
                "recognition requests with ERROR_CLIENT."
        }
        if (!defaultVisible(env)) {
            return false to "The default recognition service ($def) is not among the services " +
                "DeepSeek can see; the ROM hides it from third-party apps or it is disabled, " +
                "so Android cannot connect DeepSeek to it."
        }
        val last = env.lastError
        if (last != null && last.code == SpeechRecognizer.ERROR_CLIENT) {
            return false to "The last recognition attempt failed with ERROR_CLIENT: Android could " +
                "not connect DeepSeek to the recognition service ($def). On some ROMs (e.g. vivo " +
                "OriginOS) the default recognizer does not serve third-party apps."
        }
        if (!env.hasMicPermission) {
            return true to "Microphone permission is not granted yet; transcribe will ask for it."
        }
        return true to null
    }

    fun status(env: Environment, probe: JSONObject? = null): JSONObject {
        val (isAvailable, reason) = assess(env)
        val data = JSONObject()
            .put("available", env.services.isNotEmpty())
            .put("has_record_audio_permission", env.hasMicPermission)
            .put("is_available", isAvailable)
            .put("authorization_status", if (env.hasMicPermission) "authorized" else "not_authorized")
            .put("locale", env.locale)
            .put("supports_on_device", env.onDeviceAvailable ?: JSONObject.NULL)
            .put("recognition_services", JSONArray(env.services))
            .put("default_service", env.defaultService ?: JSONObject.NULL)
            .put("default_service_visible", defaultVisible(env))
            .put("sdk_int", env.sdkInt)
            .put("reason", reason ?: JSONObject.NULL)
        env.lastError?.let {
            data.put(
                "last_error",
                JSONObject()
                    .put("code", it.code)
                    .put("name", RecognizerErrorCodes.name(it.code))
                    .put("recognizer", it.recognizer ?: JSONObject.NULL)
                    .put("at", iso(Date(it.atMillis))),
            )
        }
        if (probe != null) data.put("probe", probe)
        return data
    }


    data class VoiceDetails(val tags: List<String>?, val note: String)

    data class Support(
        val online: List<String>,
        val installedOnDevice: List<String>,
        val supportedOnDevice: List<String>,
        val note: String,
        val regularAnswered: Boolean = true,
        val onDeviceAnswered: Boolean = true,
    ) {
        val isEmpty get() = online.isEmpty() && installedOnDevice.isEmpty() && supportedOnDevice.isEmpty()
    }

    sealed class LanguagesResult {
        abstract val body: JSONObject
        abstract val exitCode: Int

        class Found(override val body: JSONObject) : LanguagesResult() {
            override val exitCode = EXIT_OK
        }

        class Unavailable(override val body: JSONObject) : LanguagesResult() {
            override val exitCode = EXIT_NOT_AVAILABLE
        }
    }

    fun languages(
        details: VoiceDetails,
        support: Support?,
        builtin: List<String>,
        prefix: String?,
        now: Date = Date(),
    ): LanguagesResult {
        val detailTags = details.tags.orEmpty()
        val sources = JSONArray()
        if (detailTags.isNotEmpty()) sources.put("voice_details")
        if (support != null && !support.isEmpty) sources.put("recognition_support")

        val all = linkedSetOf<String>()
        all.addAll(detailTags)
        if (support != null) {
            all.addAll(support.online)
            all.addAll(support.installedOnDevice)
            all.addAll(support.supportedOnDevice)
        }
        val tags = all.map { canonicalTag(it) }.distinct().filter { matches(it, prefix) }.sorted()

        if (sources.length() == 0) {
            val fallback = JSONArray()
            builtin.map { canonicalTag(it) }.distinct().filter { matches(it, prefix) }.forEach {
                fallback.put(localeItem(it))
            }
            val extra = JSONObject()
                .put("voice_details", details.note)
                .put("recognition_support", support?.note ?: "not available below Android 13")
                .put(
                    "fallback",
                    JSONObject()
                        .put("source", "builtin")
                        .put(
                            "note",
                            "DeepSeek' built-in list (the one the chat voice input offers). It is NOT what " +
                                "this device's recognizer reported and may include languages it cannot " +
                                "recognize.",
                        )
                        .put("locales", fallback)
                        .put("count", fallback.length()),
                )
            return LanguagesResult.Unavailable(
                error(
                    "languages",
                    "unavailable",
                    "The system did not report which languages its speech recognizer supports. " +
                        "This says nothing about whether recognition works; try `android-speech " +
                        "transcribe --language <tag>` directly.",
                    extra,
                    now,
                ),
            )
        }

        val items = JSONArray()
        for (tag in tags) {
            val item = localeItem(tag)
            if (support != null && !support.isEmpty) {
                if (support.regularAnswered) item.put("online", support.online.any { canonicalTag(it) == tag })
                if (support.onDeviceAnswered) {
                    item.put("on_device_installed", support.installedOnDevice.any { canonicalTag(it) == tag })
                    item.put("on_device_supported", support.supportedOnDevice.any { canonicalTag(it) == tag })
                }
            }
            items.put(item)
        }
        val data = JSONObject()
            .put("locales", items)
            .put("count", items.length())
            .put("sources", sources)
            .put(
                "notes",
                JSONObject()
                    .put("voice_details", details.note)
                    .put("recognition_support", support?.note ?: "not available below Android 13")
                    .put(
                        "flags",
                        "online / on_device_* are what each recognizer reported about itself and are " +
                            "omitted for a recognizer that did not answer.",
                    ),
            )
        return LanguagesResult.Found(ok("languages", data, now))
    }

    private fun matches(tag: String, prefix: String?): Boolean =
        prefix.isNullOrBlank() || tag.lowercase().startsWith(canonicalTag(prefix).lowercase())

    fun canonicalTag(tag: String): String = tag.trim().replace('_', '-')

    private fun localeItem(tag: String): JSONObject {
        val name = Locale.forLanguageTag(tag).displayName
        return JSONObject().put("locale", tag).put("display_name", name.ifEmpty { tag })
    }


    fun errorCode(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_CLIENT -> "client_error"
        SpeechRecognizer.ERROR_AUDIO -> "audio_error"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_error"
        SpeechRecognizer.ERROR_SERVER -> "server_error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_denied"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no_speech"
        RecognizerErrorCodes.TOO_MANY_REQUESTS -> "too_many_requests"
        RecognizerErrorCodes.SERVER_DISCONNECTED -> "service_disconnected"
        RecognizerErrorCodes.LANGUAGE_NOT_SUPPORTED -> "language_not_supported"
        RecognizerErrorCodes.LANGUAGE_UNAVAILABLE -> "language_unavailable"
        else -> "recognizer_error"
    }

    fun recognizerErrorMessage(
        code: Int,
        language: String?,
        defaultService: String?,
        hints: List<String>,
    ): String {
        val base = when (code) {
            SpeechRecognizer.ERROR_CLIENT ->
                "Android could not connect DeepSeek to a speech recognition service (ERROR_CLIENT). " +
                    "Default service: ${defaultService ?: "none set"}. On some ROMs (e.g. vivo " +
                    "OriginOS and other China ROMs) the default recognizer does not serve " +
                    "third-party apps; that is a system restriction DeepSeek cannot change."
            RecognizerErrorCodes.LANGUAGE_NOT_SUPPORTED, RecognizerErrorCodes.LANGUAGE_UNAVAILABLE ->
                RecognizerErrorCodes.describe(code) + " (requested: ${language ?: "device default"})."
            else -> RecognizerErrorCodes.describe(code) + " (${RecognizerErrorCodes.name(code)})."
        }
        val tips = hints + "Run `android-speech status` for the services on this device."
        return base + " Try: " + tips.joinToString(" ")
    }
}
