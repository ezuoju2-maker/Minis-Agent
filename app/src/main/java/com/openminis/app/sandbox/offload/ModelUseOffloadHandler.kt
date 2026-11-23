package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Base64
import android.util.Log
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.safeOptString
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ModelUseOffloadHandler(
    private val context: Context,
    private val providerRepository: ProviderRepository,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "run" -> cmdRun(args, request)
                else -> NativeOffloadResult(2, "minis-model-use: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            NativeOffloadResult(
                1,
                JSONObject().put("error", "model_use_failed")
                    .put("message", e.message ?: "unknown").toString() + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val providerFilter = args.get("provider")?.lowercase()
        val modalityFilter = args.get("modality")?.lowercase()

        var entries = providerRepository.resolvedAgentLoopEntries()
        if (providerFilter != null) {
            entries = entries.filter {
                it.model.provider.lowercase().contains(providerFilter) ||
                    providerInstanceLabel(it).lowercase().contains(providerFilter)
            }
        }
        if (modalityFilter != null) {
            entries = entries.filter { matchesModality(it, modalityFilter) }
        }

        val body = JSONObject().apply {
            put("models", JSONArray().apply { entries.forEach { put(entryDict(it)) } })
            put("count", entries.size)
            if (entries.isEmpty()) put("hint", NO_MODELS_HINT)
            else put("usage", USAGE_HINT)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val query = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(
                2,
                "minis-model-use search: no query. Usage: minis-model-use search <query>\n",
            )
        val q = query.lowercase()
        val modalityFilter = args.get("modality")?.lowercase()

        var matches = providerRepository.resolvedAgentLoopEntries().filter { entry ->
            entry.model.id.lowercase().contains(q) ||
                entry.model.displayName.lowercase().contains(q) ||
                entry.model.provider.lowercase().contains(q)
        }
        if (modalityFilter != null) {
            matches = matches.filter { matchesModality(it, modalityFilter) }
        }

        val body = JSONObject().apply {
            put("query", query)
            put("models", JSONArray().apply { matches.forEach { put(entryDict(it)) } })
            put("count", matches.size)
            if (matches.isEmpty()) put("hint", NO_MODELS_HINT)
            else put("usage", USAGE_HINT)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    private fun cmdRun(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val caller = OffloadCallerSession.resolve(request.sessionId)
        val callerSid = caller.sid
        AppLogger.info(TAG, "[ModelUse] run caller=${callerSid?.take(8) ?: "nil"} via=${caller.source}")
        val modelArg = args.get("model")
            ?: return NativeOffloadResult(2, "--model is required. Usage: minis-model-use run --model <id_or_name>\n")

        val providerFilter = args.get("provider")

        val entry = resolveEntry(modelArg, providerFilter)
            ?: return NativeOffloadResult(
                2,
                JSONObject().put("error", "model_not_found")
                    .put(
                        "message",
                        if (providerFilter != null)
                            "No model '$modelArg' under provider '$providerFilter'. Use 'minis-model-use list' to see available combinations."
                        else
                            "Model '$modelArg' not visible to the agent. Add it in Settings > Model Groups > Available Models in Agent Loop.",
                    )
                    .toString() + "\n",
            )

        val outputPath = args.get("output")
        if (outputPath != null && !outputPath.startsWith("/") && !outputPath.startsWith("file://")) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "invalid_output_path")
                    .put(
                        "message",
                        "--output must be an absolute path (e.g. /var/minis/workspace/out.jpg). " +
                            "Got the relative path '$outputPath', which cannot be resolved because " +
                            "minis-model-use does not inherit the shell's working directory.",
                    )
                    .toString() + "\n",
            )
        }
        val outputExt = outputPath?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val outputs = entry.model.outputModalities.orEmpty()
        val requiredModality = when {
            isImageExt(outputExt) -> "image_output".takeIf { "image" !in outputs }
            isAudioExt(outputExt) -> "audio_output".takeIf { "audio" !in outputs }
            isVideoExt(outputExt) -> "video_output".takeIf { "video" !in outputs }
            else -> null
        }
        if (requiredModality != null) {
            val supported = buildList {
                if ("text" in entry.model.inputModalities.orEmpty()) add("text_input")
                if ("text" in outputs) add("text_output")
                if ("image" in entry.model.inputModalities.orEmpty()) add("image_input")
                if ("image" in outputs) add("image_output")
                if ("audio" in entry.model.inputModalities.orEmpty()) add("audio_input")
                if ("audio" in outputs) add("audio_output")
                if ("video" in entry.model.inputModalities.orEmpty()) add("video_input")
                if ("video" in outputs) add("video_output")
            }
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "modality_not_supported")
                    .put("message", "Model '${entry.model.displayName}' does not support $requiredModality. Supported modalities: ${supported.joinToString(", ")}. Use 'minis-model-use list' to find a model with the required capability.")
                    .toString() + "\n",
            )
        }

        val explicitSystem = args.get("system") ?: args.get("system-file")?.let { readLinuxPath(it) }

        val inputText = when {
            args.get("input") != null -> readLinuxPath(args.get("input")!!)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot read --input '${args.get("input")}'\n",
                )
            else -> ""
        }
        val parsed = try {
            parseMessages(inputText)
        } catch (e: ImageInputError) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "image_input_error")
                    .put("message", e.message ?: "image input could not be resolved")
                    .toString() + "\n",
            )
        } catch (e: AudioInputError) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "audio_input_error")
                    .put("message", e.message ?: "audio input could not be parsed")
                    .toString() + "\n",
            )
        }
        val inlineSystem = parsed.filter { it.role == "system" }
            .joinToString("\n") { it.content }
        val nonSystem = parsed.filter { it.role != "system" }

        if (nonSystem.any { it.audios.isNotEmpty() } &&
            "audio" !in entry.model.inputModalities.orEmpty()
        ) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "modality_not_supported")
                    .put(
                        "message",
                        "Model '${entry.model.displayName}' does not support audio_input, " +
                            "but the input contains an input_audio block. " +
                            "Use 'minis-model-use list --modality audio' to find an audio-capable model.",
                    )
                    .toString() + "\n",
            )
        }

        val messages = nonSystem.map { textMessage(it.role, it.content, it.audios) }
        val hasAudioInput = messages.any { it.audioParts.isNotEmpty() }
        val imageParts = nonSystem.lastOrNull { it.role == "user" }?.images.orEmpty()
        val systemPrompt = when {
            explicitSystem != null && inlineSystem.isNotEmpty() -> "$explicitSystem\n\n$inlineSystem"
            explicitSystem != null -> explicitSystem
            inlineSystem.isNotEmpty() -> inlineSystem
            else -> null
        }

        val maxTokens = args.get("max-tokens")?.toIntOrNull() ?: 4096
        val temperature = args.get("temperature")?.toDoubleOrNull()

        val instance = providerRepository.instance(entry.providerInstanceId)
            ?: return NativeOffloadResult(2, "minis-model-use run: provider instance not found\n")
        val apiKey = kotlinx.coroutines.runBlocking { providerRepository.requestCredential(instance) }
            ?: return NativeOffloadResult(
                2,
                JSONObject().put("error", "missing_api_key")
                    .put("message", "No API key configured for provider ${entry.model.provider}.")
                    .toString() + "\n",
            )
        val provider = ProviderFactory.create(
            instance, apiKey, entry.model, context,
            sessionId = callerSid, overrides = entry.overrides,
        )

        if (hasAudioInput && provider !is com.openminis.app.provider.openai.OpenAIProvider) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "audio_input_unsupported_provider")
                    .put(
                        "message",
                        "input_audio is only supported for OpenAI-compatible providers " +
                            "(chat/completions and responses paths) for now; provider type " +
                            "'${instance.providerType}' would silently drop the audio.",
                    )
                    .toString() + "\n",
            )
        }

        val ptSpec = parsePassthroughEnvelope(inputText)
        if (ptSpec.active) {
            val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider
                ?: return NativeOffloadResult(
                    2,
                    JSONObject().put("error", "passthrough_unsupported")
                        .put(
                            "message",
                            "passthrough mode is not supported for provider type " +
                                "'${instance.providerType}' yet — only OpenAI-compatible " +
                                "providers. Use the standard OpenAI input format instead.",
                        ).toString() + "\n",
                )
            return performRawPassthrough(
                spec = ptSpec,
                entry = entry,
                openAI = openAI,
                systemPrompt = systemPrompt,
                messages = messages,
                maxTokens = maxTokens,
                outputPath = outputPath,
                sessionId = callerSid,
            )
        }

        val callWarnings = ptSpec.warnings.toMutableList()
        val appliedExtras = JSONObject()
        (provider as? com.openminis.app.provider.openai.OpenAIProvider)?.let { openAI ->
            val chatExtra = parseChatExtraBody(inputText, callWarnings)
            if (chatExtra.isNotEmpty()) {
                openAI.chatExtraBody = chatExtra
                appliedExtras.put("extra_body_keys", JSONArray(chatExtra.keys.sorted()))
                Log.i(TAG, "[ModelUseRoute] CHAT-PASSTHROUGH extra_body keys=[${chatExtra.keys.sorted().joinToString(",")}]")
            }
            val chatHdrs = parseExtraHeaders(inputText, callWarnings)
            if (chatHdrs.isNotEmpty()) {
                openAI.chatExtraHeaders = chatHdrs
                appliedExtras.put("extra_headers_keys", JSONArray(chatHdrs.keys.sorted()))
            }
            parseCustomEndpointPath(inputText)?.let { customPath ->
                openAI.absoluteEndpointOverride = customPath
                appliedExtras.put("custom_endpoint", customPath)
                Log.i(TAG, "[ModelUseRoute] CUSTOM-ENDPOINT absolute path=$customPath")
            }
        }

        val imageRouted = tryImageGenerationRoute(
            entry = entry,
            instance = instance,
            provider = provider,
            inputJson = inputText,
            fallbackPromptMessages = nonSystem,
            inputImages = imageParts,
            outputPath = outputPath,
            outputExt = outputExt,
            sessionId = callerSid,
            callWarnings = callWarnings,
        )
        if (imageRouted != null) return attachCallFeedback(imageRouted, callWarnings, appliedExtras)

        val response = try {
            runBlocking {
                provider.sendMessage(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    imageParts = imageParts,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendMessage failed for ${entry.model.id}: ${e.message}", e)
            val base = e.message ?: "model_use_failed"
            val hint = imageParamHint(entry)
            val combined = if (hint.isEmpty()) base else "$base\n\n$hint"
            return NativeOffloadResult(
                1,
                JSONObject().put("error", "model_use_failed")
                    .put("message", combined).toString() + "\n",
            )
        }

        val sessionId = callerSid
        val mediaRequired = (outputPath != null && (isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt))) ||
            ("image" in outputs && "text" !in outputs)
        if (mediaRequired && response.mediaAttachments.isEmpty()) {
            return noMediaResult(entry, response.text, outputPath, endpoint = "chat")
        }
        val mediaFiles = JSONArray()
        val ts = System.currentTimeMillis() / 1000
        val modelSlug = entry.model.id.replace("/", "_")
        if (outputPath != null) {
            val hostFile = sessionScopedHostFile(outputPath, sessionId)
                ?: PRootKernel.resolveHostPath(outputPath)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot resolve --output '$outputPath'\n",
                )
            val firstMedia = response.mediaAttachments.firstOrNull()
            val outputIsMedia = isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt)
            if (firstMedia != null && outputIsMedia) {
                hostFile.parentFile?.mkdirs()
                hostFile.writeBytes(firstMedia.data)
                logModelUseWrite(outputPath, hostFile, sessionId)
                mediaFiles.put(JSONObject().apply {
                    put("type", firstMedia.type.value)
                    put("mime_type", firstMedia.mimeType)
                    put("path", outputPath)
                    put("size", firstMedia.data.size)
                })
            } else {
                Log.d(
                    "ModelUseImage",
                    "handler text fallback: path=$outputPath textLen=${response.text.length} " +
                        "mediaAttachments=${response.mediaAttachments.size} outputIsMedia=$outputIsMedia",
                )
                hostFile.parentFile?.mkdirs()
                hostFile.writeText(response.text)
            }
        } else if (response.mediaAttachments.isNotEmpty()) {
            val attachDir = sessionScopedHostFile("/var/minis/attachments", sessionId)
                ?: PRootKernel.resolveHostPath("/var/minis/attachments")
            if (attachDir != null) {
                attachDir.mkdirs()
                for ((idx, media) in response.mediaAttachments.withIndex()) {
                    val ext = mimeToExt(media.mimeType)
                    val fileName = "model-use-$modelSlug-$ts-$idx.$ext"
                    val hostFile = File(attachDir, fileName)
                    hostFile.writeBytes(media.data)
                    val responsePath = "/var/minis/attachments/$fileName"
                    logModelUseWrite(responsePath, hostFile, sessionId)
                    mediaFiles.put(JSONObject().apply {
                        put("type", media.type.value)
                        put("mime_type", media.mimeType)
                        put("path", responsePath)
                        put("size", media.data.size)
                    })
                }
            }
        }

        val body = JSONObject().apply {
            put("model", entry.model.id)
            put("text", response.text)
            response.usage?.let { u ->
                put("usage", JSONObject().apply {
                    put("input_tokens", u.inputTokens)
                    put("output_tokens", u.outputTokens)
                })
            }
            if (outputPath != null) put("output_file", outputPath)
            if (mediaFiles.length() > 0) put("media_files", mediaFiles)
        }
        return attachCallFeedback(
            NativeOffloadResult(0, body.toString(2) + "\n"),
            callWarnings, appliedExtras,
        )
    }

    private data class ImageGenConfig(
        val prompt: String? = null,
        val n: Int = 1,
        val size: String? = null,
        val quality: String? = null,
        val endpointOverride: com.openminis.app.data.model.ImageEndpointMode? = null,
    )

    private fun parseImageGenConfig(inputJson: String): ImageGenConfig {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return ImageGenConfig()

        var prompt: String? = null
        var n = 1
        var size: String? = null
        var quality: String? = null

        obj.optJSONObject("generation_config")?.let { gc ->
            val gn = if (gc.has("n")) gc.optInt("n", 1)
            else if (gc.has("number_of_images")) gc.optInt("number_of_images", 1) else 1
            n = maxOf(1, gn)
            size = gc.safeOptString("size", "").ifEmpty { gc.safeOptString("image_size", "").ifEmpty { null } }
            quality = gc.safeOptString("quality", "").ifEmpty { null }
        }
        obj.safeOptString("prompt", "").takeIf { it.isNotEmpty() }?.let { prompt = it }
        if (obj.has("n")) n = maxOf(1, obj.optInt("n", n))
        obj.safeOptString("size", "").takeIf { it.isNotEmpty() }?.let { size = it }
        obj.safeOptString("quality", "").takeIf { it.isNotEmpty() }?.let { quality = it }

        val rawEndpoint = obj.safeOptString("image_endpoint", "").ifEmpty {
            obj.safeOptString("endpoint", "").ifEmpty {
                obj.optJSONObject("generation_config")?.safeOptString("image_endpoint", "").orEmpty()
            }
        }
        val endpoint = when (rawEndpoint.lowercase()) {
            "auto" -> com.openminis.app.data.model.ImageEndpointMode.auto
            "images_generations", "images-generations", "images-gen" ->
                com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
            "chat_completions", "chat-completions", "chat" ->
                com.openminis.app.data.model.ImageEndpointMode.chatCompletions
            else -> null
        }
        return ImageGenConfig(prompt, n, size, quality, endpoint)
    }

    private data class ImagePassthrough(
        val body: Map<String, Any?> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val path: String? = null,
    )

    private val imageReservedKeys: Set<String> = setOf(
        "messages", "model", "chat_model", "prompt", "n", "number_of_images",
        "size", "image_size", "quality", "generation_config", "endpoint",
        "image_endpoint", "endpoint_path", "extra_body", "extra_headers",
        "stream", "temperature", "max_tokens",
    )

    private fun parseImagePassthrough(
        inputJson: String,
        warnings: MutableList<String> = mutableListOf(),
    ): ImagePassthrough {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return ImagePassthrough()

        val body = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) {
            if (key in imageReservedKeys) continue
            body[key] = obj.opt(key)
        }
        obj.opt("extra_body")?.takeIf { it != JSONObject.NULL }?.let { raw ->
            if (raw is JSONObject) {
                for (key in raw.keys()) body[key] = raw.opt(key)
            } else {
                warnings.add(
                    "extra_body is not a JSON object (actual type: ${jsonTypeName(raw)}) — ignored. " +
                        "Wrap your fields in an object: \"extra_body\":{...}.",
                )
            }
        }
        val headers = LinkedHashMap<String, String>()
        obj.opt("extra_headers")?.takeIf { it != JSONObject.NULL }?.let { raw ->
            if (raw is JSONObject) {
                for (key in raw.keys()) {
                    val v = raw.opt(key)
                    if (v is String) {
                        headers[key] = v
                    } else {
                        warnings.add(
                            "extra_headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                                "this header was ignored. Header values must be JSON strings.",
                        )
                    }
                }
            } else {
                warnings.add(
                    "extra_headers is not a JSON object (actual type: ${jsonTypeName(raw)}) — " +
                        "all custom headers were ignored.",
                )
            }
        }
        val path = obj.safeOptString("endpoint_path", "").trim().takeIf { it.isNotEmpty() }
        return ImagePassthrough(body, headers, path)
    }


    private fun jsonTypeName(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is String -> "string"
        is Boolean -> "bool"
        is Number -> "number"
        is JSONArray -> "array"
        is JSONObject -> "object"
        else -> v.javaClass.simpleName
    }

    private fun attachCallFeedback(
        result: NativeOffloadResult,
        warnings: List<String>,
        extras: JSONObject?,
    ): NativeOffloadResult {
        if (warnings.isEmpty() && (extras == null || extras.length() == 0)) return result
        val body = try {
            val t = result.output.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return result
        if (warnings.isNotEmpty()) {
            val merged = LinkedHashSet<String>()
            body.optJSONArray("warnings")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i)?.let { merged.add(it) }
            }
            merged.addAll(warnings)
            body.put("warnings", JSONArray(merged.toList()))
        }
        if (extras != null && extras.length() > 0 && !body.has("applied_extras")) {
            body.put("applied_extras", extras)
        }
        return NativeOffloadResult(result.exitCode, body.toString(2) + "\n")
    }


    private data class PassthroughSpec(
        val active: Boolean = false,
        val endpoint: String? = null,
        val method: String = "POST",
        val headers: Map<String, String> = emptyMap(),
        val body: Map<String, Any?> = emptyMap(),
        val bodyMode: String = "merge",
        val warnings: List<String> = emptyList(),
    )

    private fun parsePassthroughEnvelope(inputJson: String): PassthroughSpec {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return PassthroughSpec()

        val warnings = mutableListOf<String>()
        val envRaw: Any? = obj.opt("passthrough")
        val env = envRaw as? JSONObject
        if (env == null) {
            if (envRaw != null && envRaw != JSONObject.NULL) {
                warnings.add(
                    "Top-level 'passthrough' is not a JSON object (actual type: ${jsonTypeName(envRaw)}) — " +
                        "it was ignored and this call ran in STANDARD mode. " +
                        "Expected shape: {\"passthrough\":{\"endpoint\":...,\"body\":{...}}}.",
                )
                return PassthroughSpec(warnings = warnings)
            }
            val envelopeKeys = setOf("endpoint", "body", "body_mode", "headers", "method")
            for (key in obj.keys()) {
                if (key == "passthrough" || key in imageReservedKeys) continue
                val v = obj.opt(key) as? JSONObject ?: continue
                val normalized = key.lowercase().replace("_", "").replace("-", "")
                val looksLikeName = normalized in setOf("passthrough", "passthru", "passthough", "pathrough")
                val looksLikeShape = v.keys().asSequence().any { it in envelopeKeys }
                if (looksLikeName || looksLikeShape) {
                    warnings.add(
                        "Top-level key '$key' looks like a passthrough envelope, but the exact key " +
                            "must be lowercase 'passthrough' — it was ignored and this call ran in STANDARD mode.",
                    )
                }
            }
            return PassthroughSpec(warnings = warnings)
        }

        var endpoint: String? = null
        env.opt("endpoint")?.takeIf { it != JSONObject.NULL }?.let { epRaw ->
            val ep = (epRaw as? String)?.trim()
            if (!ep.isNullOrEmpty()) {
                endpoint = ep
            } else {
                warnings.add(
                    "passthrough.endpoint is not a non-empty string (actual type: ${jsonTypeName(epRaw)}) — " +
                        "ignored; the default chat/completions path was used.",
                )
            }
        }
        var method = "POST"
        env.opt("method")?.takeIf { it != JSONObject.NULL }?.let { mRaw ->
            val allowed = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
            val m = (mRaw as? String)?.uppercase()
            if (m != null && m in allowed) {
                method = m
            } else {
                val desc = (mRaw as? String) ?: jsonTypeName(mRaw)
                warnings.add("method value '$desc' is not supported (supported: GET/POST/PUT/DELETE/PATCH) — sent as POST.")
            }
        }
        val headers = LinkedHashMap<String, String>()
        env.opt("headers")?.takeIf { it != JSONObject.NULL }?.let { hRaw ->
            if (hRaw is JSONObject) {
                for (key in hRaw.keys()) {
                    val v = hRaw.opt(key)
                    if (v is String) {
                        headers[key] = v
                    } else {
                        warnings.add(
                            "passthrough.headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                                "this header was ignored. Header values must be JSON strings.",
                        )
                    }
                }
            } else {
                warnings.add(
                    "passthrough.headers is not a JSON object (actual type: ${jsonTypeName(hRaw)}) — " +
                        "all custom headers were ignored.",
                )
            }
        }
        val body = LinkedHashMap<String, Any?>()
        env.opt("body")?.takeIf { it != JSONObject.NULL }?.let { bRaw ->
            if (bRaw is JSONObject) {
                for (key in bRaw.keys()) body[key] = bRaw.opt(key)
            } else {
                warnings.add(
                    "passthrough.body is not a JSON object (actual type: ${jsonTypeName(bRaw)}) — " +
                        "ignored. Wrap your body fields in an object: \"body\":{...}.",
                )
            }
        }
        var bodyMode = "merge"
        env.opt("body_mode")?.takeIf { it != JSONObject.NULL }?.let { bmRaw ->
            val bm = (bmRaw as? String)?.lowercase()
            if (bm != null && bm in setOf("merge", "replace")) {
                bodyMode = bm
            } else {
                val desc = (bmRaw as? String) ?: jsonTypeName(bmRaw)
                warnings.add("body_mode value '$desc' is invalid (only merge/replace are supported) — treated as merge.")
            }
        }
        return PassthroughSpec(true, endpoint, method, headers, bodyMode = bodyMode, body = body, warnings = warnings)
    }

    private fun parseChatExtraBody(inputJson: String, warnings: MutableList<String>): Map<String, Any?> {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return emptyMap()
        val raw = obj.opt("extra_body")?.takeIf { it != JSONObject.NULL } ?: return emptyMap()
        val extra = raw as? JSONObject
        if (extra == null) {
            warnings.add(
                "extra_body is not a JSON object (actual type: ${jsonTypeName(raw)}) — ignored. " +
                    "Wrap your fields in an object: \"extra_body\":{...}.",
            )
            return emptyMap()
        }
        val out = LinkedHashMap<String, Any?>()
        for (key in extra.keys()) out[key] = extra.opt(key)
        return out
    }

    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return emptyMap()
        val raw = obj.opt("extra_headers")?.takeIf { it != JSONObject.NULL } ?: return emptyMap()
        val hdrs = raw as? JSONObject
        if (hdrs == null) {
            warnings.add(
                "extra_headers is not a JSON object (actual type: ${jsonTypeName(raw)}) — " +
                    "all custom headers were ignored.",
            )
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for (key in hdrs.keys()) {
            val v = hdrs.opt(key)
            if (v is String) {
                out[key] = v
            } else {
                warnings.add(
                    "extra_headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                        "this header was ignored. Header values must be JSON strings.",
                )
            }
        }
        return out
    }

    private fun parseCustomEndpointPath(inputJson: String): String? {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return null
        val raw = obj.safeOptString("image_endpoint", "").ifEmpty { obj.safeOptString("endpoint", "") }
            .trim()
        return if (raw.startsWith("/")) raw else null
    }

    private fun performRawPassthrough(
        spec: PassthroughSpec,
        entry: ModelEntry,
        openAI: com.openminis.app.provider.openai.OpenAIProvider,
        systemPrompt: String?,
        messages: List<LLMMessage>,
        maxTokens: Int,
        outputPath: String?,
        sessionId: String?,
    ): NativeOffloadResult {
        val warnings = spec.warnings.toMutableList()
        val bodyObject: JSONObject? = if (spec.bodyMode == "replace") {
            if (spec.body.isEmpty()) {
                warnings.add(
                    "body_mode=replace but body is empty or missing — this request was sent with NO body. " +
                        "If that is not what you intended, check that your fields are nested inside " +
                        "passthrough.body (not at the top level).",
                )
                null
            } else JSONObject().also {
                for ((k, v) in spec.body) it.put(k, v ?: JSONObject.NULL)
            }
        } else {
            val baseline = JSONObject().put("model", entry.model.id)
            if (messages.isNotEmpty()) {
                val arr = JSONArray()
                if (!systemPrompt.isNullOrEmpty()) {
                    arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                for (m in messages) {
                    arr.put(JSONObject().put("role", m.role.value).put("content", m.content))
                }
                baseline.put("messages", arr)
                baseline.put("max_tokens", maxTokens)
                baseline.put("stream", false)
            }
            for ((k, v) in spec.body) baseline.put(k, v ?: JSONObject.NULL)
            baseline.put("model", entry.model.id)
            baseline
        }

        val result = try {
            runBlocking {
                openAI.rawPassthroughRequest(
                    endpoint = spec.endpoint,
                    method = spec.method,
                    headers = spec.headers,
                    bodyObject = bodyObject,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "raw passthrough failed: ${e.message}", e)
            return NativeOffloadResult(
                1,
                JSONObject().put("error", "passthrough_failed")
                    .put("message", e.message ?: "passthrough request failed")
                    .toString() + "\n",
            )
        }

        val out = JSONObject()
            .put("passthrough", true)
            .put("http_status", result.status)
            .put("content_type", result.contentType ?: JSONObject.NULL)
            .put("bytes", result.data.size)
            .put("endpoint_url", result.url)
            .put("body_mode", spec.bodyMode)
            .put("model_id", entry.model.id)

        if (outputPath != null) {
            val hostFile = sessionScopedHostFile(outputPath, sessionId)
                ?: PRootKernel.resolveHostPath(outputPath)
            if (hostFile == null) {
                out.put("output_error", "Could not resolve --output '$outputPath'")
                inlineTextIfPossible(result.data, out)
            } else {
                try {
                    hostFile.parentFile?.mkdirs()
                    hostFile.writeBytes(result.data)
                    logModelUseWrite(outputPath, hostFile, sessionId)
                    out.put("output_file", outputPath)
                } catch (e: Throwable) {
                    out.put("output_error", "Could not write --output at $outputPath: ${e.message}")
                    inlineTextIfPossible(result.data, out)
                }
            }
        } else {
            inlineTextIfPossible(result.data, out)
        }
        if (result.status < 200 || result.status >= 300) {
            out.put(
                "error_hint",
                "Provider returned HTTP ${result.status}; the raw response body (output file or inline) is unmodified.",
            )
        }
        if (warnings.isNotEmpty()) {
            out.put("warnings", JSONArray(warnings))
        }
        Log.i(TAG, "[ModelUseRoute] raw-passthrough done status=${result.status} bytes=${result.data.size} url=${result.url} warnings=${warnings.size}")
        val code = if (result.status in 200..299) 0 else 1
        return NativeOffloadResult(code, out.toString(2) + "\n")
    }

    private fun inlineTextIfPossible(data: ByteArray, out: JSONObject) {
        val text = try { String(data, Charsets.UTF_8).takeIf { data.isEmpty() || it.toByteArray(Charsets.UTF_8).contentEquals(data) } } catch (_: Throwable) { null }
        if (text != null) {
            val capped = if (text.length > 65536) text.substring(0, 65536) + "…[truncated]" else text
            out.put("response_text", capped)
            if (text.length > 65536) out.put("truncated", true)
        } else {
            out.put("hint", "Response is binary (${data.size} bytes) and no --output was given — re-run with --output <path> to save it.")
        }
    }

    private fun looksLikeEndpointMissing(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("404") ||
            m.contains("not found") ||
            m.contains("no such endpoint") ||
            m.contains("unknown endpoint") ||
            m.contains("invalid url") ||
            m.contains("method not allowed") ||
            m.contains("unsupported route") ||
            m.contains("405")
    }

    private fun tryImageGenerationRoute(
        entry: ModelEntry,
        instance: com.openminis.app.data.model.ProviderInstance,
        provider: com.openminis.app.provider.LLMProvider,
        inputJson: String,
        fallbackPromptMessages: List<ParsedMessage>,
        inputImages: List<LLMMessage.ImagePart>,
        outputPath: String?,
        outputExt: String,
        sessionId: String?,
        callWarnings: MutableList<String> = mutableListOf(),
    ): NativeOffloadResult? {
        val outputs = entry.model.outputModalities.orEmpty()
        val wantsImageOutput = "image" in outputs
        val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider
        if (!wantsImageOutput || openAI == null) return null
        val pType = instance.providerType
        if (pType != ProviderType.openAI && pType != ProviderType.openRouter && pType != ProviderType.xAI) return null
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) return null

        val cfg = parseImageGenConfig(inputJson)
        val prompt = cfg.prompt
            ?: fallbackPromptMessages.lastOrNull { it.role == "user" }?.content
            ?: ""

        val passthrough = parseImagePassthrough(inputJson, callWarnings)
        openAI.imageExtraBody = passthrough.body
        openAI.imagePathOverride = passthrough.path
        openAI.imageExtraHeaders = passthrough.headers
        if (passthrough.body.isNotEmpty() || passthrough.path != null || passthrough.headers.isNotEmpty()) {
            Log.i(
                TAG,
                "[ModelUseRoute] PASSTHROUGH bodyKeys=[${passthrough.body.keys.sorted().joinToString(",")}] " +
                    "pathOverride=${passthrough.path ?: "<nil>"} " +
                    "extraHeaderKeys=[${passthrough.headers.keys.sorted().joinToString(",")}]",
            )
        }

        val isPureImageGenerator = "image" in outputs && "text" !in outputs

        if (inputImages.isNotEmpty() && !isPureImageGenerator) {
            Log.i(
                TAG,
                "[ModelUseRoute] input images present for ${entry.model.id} (text+image model) — declining images route, falling through to chat so images are forwarded",
            )
            return null
        }

        val effectiveMode = when {
            cfg.endpointOverride != null -> cfg.endpointOverride
            isPureImageGenerator -> com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
            instance.imageEndpointMode == com.openminis.app.data.model.ImageEndpointMode.auto &&
                instance.imageEndpointResolved != null -> instance.imageEndpointResolved!!
            else -> instance.imageEndpointMode
        }
        Log.i(
            TAG,
            "[ModelUseRoute] image route model=${entry.model.id} mode=${instance.imageEndpointMode} " +
                "resolved=${instance.imageEndpointResolved} pure=$isPureImageGenerator override=${cfg.endpointOverride} " +
                "effective=$effectiveMode",
        )

        if (effectiveMode == com.openminis.app.data.model.ImageEndpointMode.chatCompletions) return null

        if (effectiveMode == com.openminis.app.data.model.ImageEndpointMode.imagesGenerations) {
            val response = try {
                runBlocking {
                    if (inputImages.isEmpty()) {
                        openAI.generateImage(prompt, cfg.n, cfg.size, cfg.quality)
                    } else {
                        openAI.editImage(prompt, inputImages, cfg.n, cfg.size, cfg.quality)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "[ModelUseRoute] images/${if (inputImages.isEmpty()) "generations" else "edits"} (forced) failed: ${e.message}", e)
                val hint = imageParamHint(entry)
                val base = e.message ?: "image_generation_failed"
                return NativeOffloadResult(
                    1,
                    JSONObject().put("error", "image_generation_failed")
                        .put("message", if (hint.isEmpty()) base else "$base\n\n$hint")
                        .toString() + "\n",
                )
            }
            return writeImageResult(entry, response, outputPath, outputExt, "images_generations", sessionId)
        }

        val response = try {
            runBlocking {
                if (inputImages.isEmpty()) {
                    openAI.generateImage(prompt, cfg.n, cfg.size, cfg.quality)
                } else {
                    openAI.editImage(prompt, inputImages, cfg.n, cfg.size, cfg.quality)
                }
            }.also {
                providerRepository.setImageEndpointResolved(
                    instance.id, com.openminis.app.data.model.ImageEndpointMode.imagesGenerations,
                )
            }
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            val routeMissing = (e is com.openminis.app.data.model.LLMError.ProviderError ||
                e is com.openminis.app.data.model.LLMError.InvalidApiKey) && looksLikeEndpointMissing(msg)
            if (routeMissing) {
                Log.i(TAG, "[ModelUseRoute] auto probe → route missing (${msg.take(120)}) — caching chat_completions, falling back")
                providerRepository.setImageEndpointResolved(
                    instance.id, com.openminis.app.data.model.ImageEndpointMode.chatCompletions,
                )
                return null
            }
            Log.w(TAG, "[ModelUseRoute] auto probe failed (non-route): ${e.message}", e)
            val hint = imageParamHint(entry)
            val base = e.message ?: "image_generation_failed"
            return NativeOffloadResult(
                1,
                JSONObject().put("error", "image_generation_failed")
                    .put("message", if (hint.isEmpty()) base else "$base\n\n$hint")
                    .toString() + "\n",
            )
        }
        return writeImageResult(entry, response, outputPath, outputExt, "images_generations", sessionId)
    }

    private fun writeImageResult(
        entry: ModelEntry,
        response: com.openminis.app.data.model.LLMResponse,
        outputPath: String?,
        outputExt: String,
        endpointUsed: String,
        sessionId: String?,
    ): NativeOffloadResult {
        val mediaFiles = JSONArray()
        val firstMedia = response.mediaAttachments.firstOrNull()
        if (firstMedia == null) {
            return noMediaResult(entry, response.text, outputPath, endpoint = endpointUsed)
        }
        val ts = System.currentTimeMillis() / 1000
        val modelSlug = entry.model.id.replace("/", "_")
        if (outputPath != null) {
            val hostFile = sessionScopedHostFile(outputPath, sessionId)
                ?: PRootKernel.resolveHostPath(outputPath)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot resolve --output '$outputPath'\n",
                )
            val outputIsMedia = isImageExt(outputExt)
            if (firstMedia != null && outputIsMedia) {
                hostFile.parentFile?.mkdirs()
                hostFile.writeBytes(firstMedia.data)
                logModelUseWrite(outputPath, hostFile, sessionId)
                mediaFiles.put(JSONObject().apply {
                    put("type", firstMedia.type.value)
                    put("mime_type", firstMedia.mimeType)
                    put("path", outputPath)
                    put("size", firstMedia.data.size)
                })
            } else {
                hostFile.parentFile?.mkdirs()
                hostFile.writeText(response.text)
            }
        } else if (response.mediaAttachments.isNotEmpty()) {
            val attachDir = sessionScopedHostFile("/var/minis/attachments", sessionId)
                ?: PRootKernel.resolveHostPath("/var/minis/attachments")
            if (attachDir != null) {
                attachDir.mkdirs()
                for ((idx, media) in response.mediaAttachments.withIndex()) {
                    val ext = mimeToExt(media.mimeType)
                    val fileName = "model-use-$modelSlug-$ts-$idx.$ext"
                    val hostFile = File(attachDir, fileName)
                    hostFile.writeBytes(media.data)
                    val responsePath = "/var/minis/attachments/$fileName"
                    logModelUseWrite(responsePath, hostFile, sessionId)
                    mediaFiles.put(JSONObject().apply {
                        put("type", media.type.value)
                        put("mime_type", media.mimeType)
                        put("path", responsePath)
                        put("size", media.data.size)
                    })
                }
            }
        }
        val body = JSONObject().apply {
            put("model", entry.model.id)
            put("text", response.text)
            put("image_endpoint", endpointUsed)
            if (outputPath != null) put("output_file", outputPath)
            if (mediaFiles.length() > 0) put("media_files", mediaFiles)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    private fun noMediaResult(
        entry: ModelEntry,
        text: String,
        outputPath: String?,
        endpoint: String,
    ): NativeOffloadResult {
        Log.w(TAG, "[ModelUseNoMedia] model=${entry.model.id} endpoint=$endpoint output=$outputPath textLen=${text.length}")
        val body = JSONObject().apply {
            put("error", "no_media_generated")
            put(
                "message",
                "The model returned no image/media (often a content-policy refusal or quota limit). " +
                    "Nothing was saved" + (outputPath?.let { " to $it" } ?: "") +
                    ". Do not reference a generated file; tell the user it failed and why.",
            )
            put("model", entry.model.id)
            put("endpoint", endpoint)
            if (text.isNotBlank()) put("model_text", text.take(2000))
        }
        return NativeOffloadResult(1, body.toString(2) + "\n")
    }

    private fun sessionScopedHostFile(linuxPath: String, sessionId: String?): File? {
        if (sessionId == null) return null
        val m = Regex("^/var/minis/(attachments|offloads|workspace|browser)(/.*)?$").find(linuxPath)
            ?: return null
        val sub = m.groupValues[1]
        val rest = m.groupValues[2].removePrefix("/")
        val base = File(context.filesDir, "minis-sessions/$sessionId/$sub")
        return if (rest.isEmpty()) base else File(base, rest)
    }

    private fun logModelUseWrite(responsePath: String, hostFile: File, sessionId: String?) {
        Log.i(
            "ModelUseImage",
            "[ModelUseWrite] path=$responsePath hostFile=${hostFile.absolutePath} " +
                "sessionId=$sessionId",
        )
    }

    private fun imageParamHint(entry: ModelEntry): String {
        if ("image" !in entry.model.outputModalities.orEmpty()) return ""
        val pType = providerRepository.instance(entry.providerInstanceId)?.providerType
        return when (pType) {
            ProviderType.gemini -> """
                Hint — ${entry.model.displayName} is a Gemini image model. Pass image params under `generation_config` in the input JSON:
                  aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
                  image_size         "512px" | "1K" | "2K" | "4K"
                  number_of_images   1-4
                  person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
                Example:
                  {"messages":[{"role":"user","content":"<prompt>"}],
                   "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}
            """.trimIndent()
            ProviderType.openAI, ProviderType.openRouter, ProviderType.openAIResponses -> """
                Hint — ${entry.model.displayName} is an OpenAI-compatible image model. Pass image params at the top level of the input JSON (matches /v1/images/generations):
                  n         integer, number of images (default 1)
                  size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
                  quality   "standard" | "hd"
                  prompt    string (overrides last user message)
                Example:
                  {"prompt":"<prompt>","size":"1792x1024","quality":"hd","n":1}

                Passthrough (topics: image-to-image, extra_body, extra_headers, endpoint_path) — any field our schema doesn't model is forwarded VERBATIM into the /images/generations JSON body, so you can drive provider-specific params (e.g. Volcengine Seedream image-to-image via an `image` field, `seed`, `tools`). Two ways, both optional & additive:
                  • implicit: any unknown TOP-LEVEL key → request body
                  • explicit: `extra_body` (object, merged into body), `extra_headers` (string map, added to request headers), `endpoint_path` (string, overrides "/images/generations" for non-standard endpoints)
                Your keys win over our defaults; `model` is always forced to the resolved id. If you set `response_format`, the b64_json auto-probe is skipped.
                Image-to-image example (Seedream — image goes in the BODY, not messages):
                  {"prompt":"make the eyes blue","image":"data:image/png;base64,<...>","size":"2K"}
                Explicit-envelope example:
                  {"messages":[{"role":"user","content":"<prompt>"}],"extra_body":{"image":"<url-or-data-uri>","seed":42},"extra_headers":{"X-Custom":"1"},"endpoint_path":"/api/v3/images/generations"}
            """.trimIndent()
            ProviderType.anthropic, ProviderType.xAI, ProviderType.kimiCode,
            ProviderType.githubCopilot,
            ProviderType.antigravity, ProviderType.unsupported, ProviderType.workBuddy,
            ProviderType.qoder, ProviderType.minimaxCode, null -> ""
        }
    }

    private fun isImageExt(ext: String): Boolean =
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "heic")

    private fun isAudioExt(ext: String): Boolean =
        ext in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")

    private fun isVideoExt(ext: String): Boolean =
        ext in setOf("mp4", "mov", "webm", "mkv")

    private fun mimeToExt(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("wav") -> "wav"
        mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
        mime.contains("mp4") -> "mp4"
        mime.contains("ogg") -> "ogg"
        else -> mime.substringAfterLast("/", "bin")
    }


    private fun resolveEntry(idOrName: String, providerFilter: String? = null): ModelEntry? {
        val all = providerRepository.resolvedAgentLoopEntries()

        val pool = if (providerFilter.isNullOrEmpty()) all else {
            val pf = providerFilter.lowercase()
            all.filter { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null && (inst.label.lowercase() == pf || inst.id.lowercase() == pf)
            }
        }
        if (pool.isEmpty()) return null

        val slash = idOrName.indexOf('/')
        if (slash > 0 && slash < idOrName.length - 1) {
            val labelPart = idOrName.substring(0, slash).lowercase()
            val modelPart = idOrName.substring(slash + 1).lowercase()
            pool.find { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null &&
                    inst.label.lowercase() == labelPart &&
                    entry.model.id.lowercase() == modelPart
            }?.let { return it }
        }

        pool.find { it.model.id == idOrName }?.let { return it }
        pool.find { it.model.displayName == idOrName }?.let { return it }
        pool.find { it.id == idOrName }?.let { return it }
        val q = idOrName.lowercase()
        pool.find { it.model.id.lowercase().startsWith(q) }?.let { return it }
        pool.find { it.model.displayName.lowercase().contains(q) }?.let { return it }
        return null
    }

    private fun providerInstanceLabel(entry: ModelEntry): String =
        providerRepository.instance(entry.providerInstanceId)?.label.orEmpty()

    private fun entryDict(entry: ModelEntry): JSONObject =
        com.openminis.app.offload.ModelUseManager.entryDict(
            entry,
            providerRepository.instance(entry.providerInstanceId),
        )

    private fun matchesModality(entry: ModelEntry, filter: String): Boolean {
        val inputs = entry.model.inputModalities.orEmpty()
        val outputs = entry.model.outputModalities.orEmpty()
        val required = filter.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (term in required) {
            val ok = when (term) {
                "text", "text_input" -> "text" in inputs || inputs.isEmpty()
                "text_output" -> "text" in outputs || outputs.isEmpty()
                "image", "image_input" -> "image" in inputs
                "image_output" -> "image" in outputs
                "audio", "audio_input" -> "audio" in inputs
                "audio_output" -> "audio" in outputs
                "video", "video_input" -> "video" in inputs
                "video_output" -> "video" in outputs
                "pdf", "pdf_input" -> "pdf" in inputs
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    private fun readLinuxPath(linuxPath: String): String? {
        val hostFile: File = PRootKernel.resolveHostPath(linuxPath) ?: return null
        if (!hostFile.exists() || !hostFile.isFile) return null
        return try { hostFile.readText() } catch (_: Throwable) { null }
    }

    private data class ParsedMessage(
        val role: String,
        val content: String,
        val images: List<LLMMessage.ImagePart>,
        val audios: List<LLMMessage.AudioPart> = emptyList(),
    )

    private fun parseMessages(text: String): List<ParsedMessage> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        try {
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val arr = obj.optJSONArray("messages")
                    ?: return listOf(ParsedMessage("user", trimmed, emptyList()))
                return parseMessageArray(arr)
            }
            if (trimmed.startsWith("[")) {
                return parseMessageArray(JSONArray(trimmed))
            }
        } catch (e: ImageInputError) {
            throw e
        } catch (e: AudioInputError) {
            throw e
        } catch (_: Throwable) {
        }
        return listOf(ParsedMessage("user", trimmed, emptyList()))
    }

    private fun parseMessageArray(arr: JSONArray): List<ParsedMessage> {
        val out = mutableListOf<ParsedMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val textBuf = StringBuilder()
            val imgs = mutableListOf<LLMMessage.ImagePart>()
            val auds = mutableListOf<LLMMessage.AudioPart>()
            when (val c = m.opt("content")) {
                is String -> textBuf.append(c)
                is JSONArray -> {
                    for (j in 0 until c.length()) {
                        val part = c.optJSONObject(j) ?: continue
                        val type = part.optString("type")
                        if (type == "text" || type.isEmpty()) {
                            textBuf.append(part.optString("text", ""))
                        } else if (type == "image_url") {
                            val imgObj = part.optJSONObject("image_url") ?: continue
                            val url = imgObj.optString("url", "").takeIf { it.isNotEmpty() }
                                ?: continue
                            try {
                                imgs.add(resolveImageUrl(url))
                            } catch (e: ImageInputError) {
                                throw e
                            }
                        } else if (type == "input_audio") {
                            val audioObj = part.optJSONObject("input_audio")
                                ?: throw AudioInputError(
                                    "input_audio block is malformed. Expected " +
                                        "{\"type\":\"input_audio\",\"input_audio\":" +
                                        "{\"data\":\"<base64>\",\"format\":\"wav\"}}.",
                                )
                            val b64 = audioObj.optString("data", "")
                            if (b64.isEmpty()) {
                                throw AudioInputError("input_audio.data is missing or empty.")
                            }
                            try {
                                Base64.decode(b64, Base64.DEFAULT)
                            } catch (e: IllegalArgumentException) {
                                throw AudioInputError("input_audio.data is not valid base64: ${e.message}")
                            }
                            val format = audioObj.optString("format", "wav").lowercase()
                            auds.add(LLMMessage.AudioPart(format = format, base64Data = b64))
                        }
                    }
                }
                else -> {  }
            }
            if (textBuf.isEmpty() && imgs.isEmpty() && auds.isEmpty()) continue
            out.add(ParsedMessage(role, textBuf.toString(), imgs, auds))
        }
        return out
    }

    private fun resolveImageUrl(url: String): LLMMessage.ImagePart {
        if (url.startsWith("data:")) {
            val rest = url.substring(5)
            val semi = rest.indexOf(';')
            val comma = rest.indexOf(',')
            if (semi <= 0 || comma <= semi) {
                throw ImageInputError("Malformed data: URL — expected `data:<mime>;base64,<...>`")
            }
            val mime = rest.substring(0, semi)
            val b64 = rest.substring(comma + 1)
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw ImageInputError("data: URL base64 payload failed to decode: ${e.message}")
            }
            return LLMMessage.ImagePart(data = bytes, mimeType = mime)
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            throw ImageInputError(
                "http(s):// image URLs are not supported by minis-model-use. " +
                    "Download first with `shell_execute` (curl/wget) into " +
                    "/var/minis/workspace/, then reference the local path."
            )
        }

        val linuxPath = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            url.startsWith("/") -> url
            else -> throw ImageInputError(
                "Unsupported image_url '$url'. Use a data: URL, file:///host/path, " +
                    "/var/minis/<scope>/<path>, or an absolute Linux path."
            )
        }
        val hostFile: File = PRootKernel.resolveHostPath(linuxPath)
            ?: File(linuxPath).takeIf { it.exists() && it.isFile }
            ?: throw ImageInputError("Image file not found at '$url'.")
        if (!hostFile.exists() || !hostFile.isFile) {
            throw ImageInputError("Image file not found at '$url' (resolved to ${hostFile.absolutePath}).")
        }
        val bytes = try {
            hostFile.readBytes()
        } catch (e: Throwable) {
            throw ImageInputError("Image file at '$url' is unreadable: ${e.message}")
        }
        return LLMMessage.ImagePart(
            data = bytes,
            mimeType = imageMimeForExtension(linuxPath),
            linuxPath = linuxPath,
        )
    }

    private fun imageMimeForExtension(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }

    private class ImageInputError(message: String) : RuntimeException(message)

    private class AudioInputError(message: String) : RuntimeException(message)

    private fun textMessage(
        role: String,
        content: String,
        audios: List<LLMMessage.AudioPart> = emptyList(),
    ): LLMMessage {
        val r = when (role.lowercase()) {
            "assistant" -> LLMMessage.Role.ASSISTANT
            else -> LLMMessage.Role.USER
        }
        return LLMMessage(role = r, content = content, audioParts = audios)
    }

    companion object {
        private const val TAG = "ModelUseOffload"
        private const val NO_MODELS_HINT =
            "No models available. Go to Settings > Model Groups to add models that the agent can use."

        private const val USAGE_HINT =
            "To invoke a model, pass `--model <model_id>` to `minis-model-use run`. " +
            "If multiple providers expose the same `model_id`, disambiguate either with " +
            "`--model <instance_label>/<model_id>` (e.g. `--model deepseek/deepseek-v4-flash`) " +
            "or with `--model <model_id> --provider <instance_label>` " +
            "(e.g. `--model deepseek-v4-flash --provider deepseek`). " +
            "The opaque `entry_id` (UUID) is also accepted. " +
            "Capabilities by modality (run without --model, or inspect a model's `hint` field, " +
            "for the exact JSON shape): text generation, image generation, image-to-image, " +
            "image editing, audio (TTS/STT), embeddings. " +
            "Topics/params you can pass per call: messages, generation_config (size/quality/n/" +
            "aspect_ratio/image_size), image_endpoint, and — for OpenAI-compatible image models — " +
            "PASSTHROUGH via `extra_body`, `extra_headers`, `endpoint_path`, or any unknown " +
            "top-level field (forwarded verbatim; enables provider-specific params like Seedream " +
            "image-to-image). Read the target model's `hint` for concrete examples."

        private const val HELP = """minis-model-use — list, search, and invoke LLM models

Usage:
  minis-model-use list [--provider <name>] [--modality <mod>]
  minis-model-use search <query> [--provider <name>] [--modality <mod>]
  minis-model-use run --model <id_or_name> [--provider <label_or_id>]
                      [--input <path>] [--output <path>]
                      [--system <text>] [--system-file <path>]
                      [--max-tokens N] [--temperature F]

Run model selection:
  --model accepts: model_id, display name, entry_id (UUID), OR the qualified
  form `<instance_label>/<model_id>` (e.g. `deepseek/deepseek-v4-flash`) to
  disambiguate when the same model_id is configured under multiple providers.
  Equivalently, pass `--model <model_id> --provider <instance_label>`.

Input format (OpenAI Chat Completions JSON — the ONLY input format):
  Standard fields (messages, system, temperature, max_tokens,
  generation_config) are AUTOMATICALLY CONVERTED to whatever the selected
  model's provider expects on the wire. Do NOT hand-write provider-native
  bodies as the primary input.

  AUDIO INPUT (audio_input models, e.g. gpt-4o-audio / Qwen-Audio):
    Add an input_audio content block to a user message (official OpenAI
    shape; forwarded verbatim on both the Chat Completions and Responses
    API paths). The model MUST declare the audio_input modality (see
    `list --modality audio`), otherwise the call fails with an explicit
    modality error.
    {"messages":[{"role":"user","content":[
       {"type":"text","text":"Transcribe this audio"},
       {"type":"input_audio","input_audio":{"data":"<base64>",
        "format":"wav"}}]}]}
    NOTE: dedicated STT transcription endpoints (POST
    /v1/audio/transcriptions on speaches / faster-whisper / whisper.cpp
    servers) require multipart/form-data uploads, which this tool does
    NOT support yet — passthrough mode sends JSON bodies only (known
    limitation). For speech-to-text today, use a chat-protocol audio
    model as above.

  Standard-mode extras (OpenAI-compatible providers, all endpoints):
    extra_body     object — merged VERBATIM into the final request body
                   (your keys win; `model` stays locked). Use for provider
                   fields our schema doesn't model, e.g. OpenRouter web
                   search: {"extra_body":{"plugins":[{"id":"web"}]}} — these
                   are NOT converted.
    extra_headers  string map — added to request headers; same-name REPLACES
                   the default (incl. auth headers).
    endpoint       "/abs/path" starting with "/" replaces the ENTIRE path on
                   the provider's own base URL host (credentials never leave
                   the host); body is still auto-converted, response parsed.

Passthrough mode (raw escape hatch; response is NOT parsed):
  For endpoints/formats our schema doesn't model (video gen, TTS,
  provider-native APIs), add a top-level "passthrough" object:
    {"passthrough": {
       "endpoint": "/v1/audio/speech",  // "/abs/path" replaces the whole
                                         // path on the provider base host;
                                         // omit = chat path
       "method":  "POST",               // default POST
       "headers": {"X-Custom": "1"},    // same-name REPLACES defaults
       "body":    {"input": "hi", "voice": "alloy"},
       "body_mode": "replace"           // replace = body IS the whole request
                                        // body; merge (default) = layered
                                        // over the converted OpenAI baseline,
                                        // model locked
    }}
  Passthrough values are sent VERBATIM (no conversion). The response is
  returned RAW: full bytes to --output (required for binary), or inline text
  up to 64KB; the result reports http_status, content_type, bytes and the
  fully-assembled endpoint_url. OpenAI-compatible providers only for now.

Image generation fields (only for image_output models):
  Pass image params either at the top level OR under "generation_config".
  Top level matches OpenAI /v1/images/generations and takes precedence.

  OpenAI-style (DALL-E / gpt-image-1 etc.):
    n         integer, number of images (default 1)
    size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
    quality   "standard" | "hd"
    prompt    string (overrides last user message)

  Gemini-style (Imagen / gemini-2.5-flash-image etc.) — under generation_config:
    aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
    image_size         "512px" | "1K" | "2K" | "4K"
    number_of_images   1-4
    person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
  Unknown providers silently ignore unsupported fields.

  Example (OpenAI):
    {"prompt":"a red panda astronaut","size":"1792x1024","quality":"hd","n":1}
  Example (Gemini):
    {"messages":[{"role":"user","content":"a red panda astronaut"}],
     "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}

Examples:
  minis-model-use list
  minis-model-use list --modality image_input
  minis-model-use search gemini
  minis-model-use run --model claude-sonnet-4-6 --input /var/minis/workspace/prompt.json
  minis-model-use run --model deepseek/deepseek-v4-flash --input msgs.json   # qualified form
  minis-model-use run --model deepseek-v4-flash --provider deepseek --input msgs.json   # equivalent
  echo 'What is 2+2?' | minis-model-use run --model gpt-4o
  minis-model-use run --model gemini-2.5-flash --system 'You are a poet' \
                      --input msgs.json --output /var/minis/workspace/out.txt
"""
    }
}
