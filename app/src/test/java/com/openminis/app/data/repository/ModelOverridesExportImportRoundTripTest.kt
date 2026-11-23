package com.openminis.app.data.repository

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOverridesExportImportRoundTripTest {


    private val bitTextIn = 1 shl 0
    private val bitTextOut = 1 shl 1
    private val bitImgIn = 1 shl 2
    private val bitPdfIn = 1 shl 3
    private val bitAudIn = 1 shl 4
    private val bitVidIn = 1 shl 5
    private val bitImgOut = 1 shl 6
    private val bitAudOut = 1 shl 7
    private val bitVidOut = 1 shl 8

    private fun bitfieldFromLists(inputs: List<String>?, outputs: List<String>?): Int {
        var bits = 0
        inputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or bitTextIn
                "image" -> bits = bits or bitImgIn
                "pdf" -> bits = bits or bitPdfIn
                "audio" -> bits = bits or bitAudIn
                "video" -> bits = bits or bitVidIn
            }
        }
        outputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or bitTextOut
                "image" -> bits = bits or bitImgOut
                "audio" -> bits = bits or bitAudOut
                "video" -> bits = bits or bitVidOut
            }
        }
        return bits
    }

    private fun listsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
        if (bits == 0) return null to null
        val inputs = buildList {
            if (bits and bitTextIn != 0) add("text")
            if (bits and bitImgIn != 0) add("image")
            if (bits and bitPdfIn != 0) add("pdf")
            if (bits and bitAudIn != 0) add("audio")
            if (bits and bitVidIn != 0) add("video")
        }
        val outputs = buildList {
            if (bits and bitTextOut != 0) add("text")
            if (bits and bitImgOut != 0) add("image")
            if (bits and bitAudOut != 0) add("audio")
            if (bits and bitVidOut != 0) add("video")
        }
        return inputs.ifEmpty { null } to outputs.ifEmpty { null }
    }

    private fun thinkingLevelFromWire(raw: String): ThinkingLevel? {
        if (raw.isEmpty()) return null
        return ThinkingLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

    private fun readModalities(obj: JSONObject): Pair<List<String>?, List<String>?> {
        val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                .takeIf { it.isNotEmpty() }
        }
        val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                .takeIf { it.isNotEmpty() }
        }
        if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
        if (!obj.has("modalityOverride")) return null to null
        return listsFromBitfield(obj.optInt("modalityOverride", 0))
    }


    private fun exportEntry(entry: ModelEntry): JSONObject = JSONObject().apply {
        put("modelId", entry.baseModel.id)
        put("displayName", entry.baseModel.displayName)
        put("isHidden", entry.isHidden)
        if (entry.isCustom) put("isCustom", true)
        entry.baseModel.contextWindow?.let { put("contextWindow", it) }
        entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
        entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
        entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
        if (!entry.overrides.isEmpty) {
            val o = JSONObject()
            entry.overrides.displayName?.let { o.put("displayName", it) }
            entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
            entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
            entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
            entry.overrides.inputModalities?.let { o.put("inputModalities", JSONArray(it)) }
            entry.overrides.outputModalities?.let { o.put("outputModalities", JSONArray(it)) }
            entry.overrides.maxThinkingLevel?.let { o.put("maxThinkingLevel", it.name.lowercase()) }
            entry.overrides.temperature?.let { o.put("temperature", it) }
            entry.overrides.topP?.let { o.put("topP", it) }
            entry.overrides.customHeaders?.let { headers ->
                if (headers.isNotEmpty()) {
                    val h = JSONObject()
                    for ((k, v) in headers) h.put(k, v)
                    o.put("customHeaders", h)
                }
            }
            entry.overrides.extraBodyParams?.let { extra ->
                runCatching { JSONObject(extra.toString()) }.getOrNull()
                    ?.let { o.put("extraBodyParams", it) }
            }
            val bitfield = bitfieldFromLists(
                entry.overrides.inputModalities,
                entry.overrides.outputModalities,
            )
            if (bitfield != 0) o.put("modalityOverride", bitfield)
            put("overrides", o)
        }
        run {
            val bf = bitfieldFromLists(entry.baseModel.inputModalities, entry.baseModel.outputModalities)
            if (bf != 0) put("modalityOverride", bf)
        }
        entry.baseModel.inputModalities?.let { put("inputModalities", JSONArray(it)) }
        entry.baseModel.outputModalities?.let { put("outputModalities", JSONArray(it)) }
    }


    private fun importEntry(m: JSONObject, providerType: ProviderType): ModelEntry {
        val modelId = m.optString("modelId", "")
        val displayName = m.optString("displayName", modelId)
        val isCustom = m.optBoolean("isCustom", false)
        val isHidden = m.optBoolean("isHidden", false)
        val contextWindow =
            if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
        val maxOutputTokens =
            if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
        val supportsReasoning =
            if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
        val interleavedReasoningField =
            m.optString("interleavedReasoningField", "").ifEmpty { null }
        val (baseIn, baseOut) = readModalities(m)
        val model = LLMModel(
            id = modelId,
            displayName = displayName,
            provider = providerType.displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            supportsReasoning = supportsReasoning,
            interleavedReasoningField = interleavedReasoningField,
            inputModalities = baseIn,
            outputModalities = baseOut,
        )
        val overridesObj = m.optJSONObject("overrides")
        val overrides = if (overridesObj != null) {
            val (ovIn, ovOut) = readModalities(overridesObj)
            ModelOverrides(
                displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                maxOutputTokens = if (overridesObj.has("maxOutputTokens")) {
                    overridesObj.optInt("maxOutputTokens").takeIf { it > 0 }
                } else null,
                contextWindow = if (overridesObj.has("contextWindow")) {
                    overridesObj.optInt("contextWindow").takeIf { it > 0 }
                } else null,
                supportsReasoning = if (overridesObj.has("supportsReasoning")) {
                    overridesObj.optBoolean("supportsReasoning")
                } else null,
                inputModalities = ovIn,
                outputModalities = ovOut,
                maxThinkingLevel = thinkingLevelFromWire(
                    overridesObj.optString("maxThinkingLevel", ""),
                ),
                temperature = if (overridesObj.has("temperature")) {
                    overridesObj.optDouble("temperature").takeIf { !it.isNaN() }
                } else null,
                topP = if (overridesObj.has("topP")) {
                    overridesObj.optDouble("topP").takeIf { !it.isNaN() }
                } else null,
                customHeaders = overridesObj.optJSONObject("customHeaders")?.let { h ->
                    buildMap { for (k in h.keys()) put(k, h.optString(k, "")) }
                        .takeIf { it.isNotEmpty() }
                },
                extraBodyParams = overridesObj.optJSONObject("extraBodyParams")?.let { ex ->
                    runCatching { Json.parseToJsonElement(ex.toString()) as? JsonObject }.getOrNull()
                },
            )
        } else {
            ModelOverrides()
        }
        return ModelEntry(
            providerInstanceId = "imported",
            baseModel = model,
            overrides = overrides,
            isCustom = isCustom,
            isHidden = isHidden,
        )
    }

    private fun roundTrip(entry: ModelEntry, type: ProviderType = ProviderType.openAI) =
        importEntry(JSONObject(exportEntry(entry).toString()), type)


    private val fullOverrides = ModelOverrides(
        displayName = "Tuned Relay Model",
        maxOutputTokens = 32_000,
        contextWindow = 262_144,
        supportsReasoning = true,
        inputModalities = listOf("text", "image", "pdf"),
        outputModalities = listOf("text"),
        temperature = 0.35,
        topP = 0.9,
        customHeaders = mapOf("X-Tenant" to "acme", "X-Trace" to "abc123"),
        extraBodyParams = JsonObject(
            mapOf(
                "reasoning_effort" to JsonPrimitive("high"),
                "seed" to JsonPrimitive(42),
            ),
        ),
    )

    @Test
    fun `every serialized override field survives the round trip`() {
        val original = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel("relay/tuned", "Relay Tuned", "Custom"),
            overrides = fullOverrides,
            isCustom = true,
            isHidden = true,
        )
        val back = roundTrip(original)

        assertEquals("Tuned Relay Model", back.overrides.displayName)
        assertEquals(32_000, back.overrides.maxOutputTokens)
        assertEquals(262_144, back.overrides.contextWindow)
        assertEquals(true, back.overrides.supportsReasoning)
        assertEquals(listOf("text", "image", "pdf"), back.overrides.inputModalities)
        assertEquals(listOf("text"), back.overrides.outputModalities)
        assertEquals(0.35, back.overrides.temperature!!, 1e-9)
        assertEquals(0.9, back.overrides.topP!!, 1e-9)
        assertEquals(mapOf("X-Tenant" to "acme", "X-Trace" to "abc123"), back.overrides.customHeaders)
        assertEquals(
            "high",
            (back.overrides.extraBodyParams!!["reasoning_effort"] as JsonPrimitive).content,
        )
        assertEquals("42", (back.overrides.extraBodyParams!!["seed"] as JsonPrimitive).content)
        assertTrue(back.isCustom)
        assertTrue(back.isHidden)
    }

    @Test
    fun `a second round trip changes nothing`() {
        val original = ModelEntry("inst", LLMModel("relay/tuned", "Relay Tuned", "Custom"), fullOverrides)
        val once = roundTrip(original)
        val twice = roundTrip(once)
        assertEquals(once.overrides, twice.overrides)
        assertEquals(once.baseModel, twice.baseModel)
    }

    @Test
    fun `the base model layer survives alongside the overrides`() {
        val original = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel(
                id = "relay/vision-r1", displayName = "Vision R1", provider = "Custom",
                contextWindow = 128_000, maxOutputTokens = 8_192, supportsReasoning = false,
                inputModalities = listOf("text", "image"), outputModalities = listOf("text"),
            ),
        )
        val back = roundTrip(original)
        assertEquals(128_000, back.baseModel.contextWindow)
        assertEquals(8_192, back.baseModel.maxOutputTokens)
        assertEquals(false, back.baseModel.supportsReasoning)
        assertEquals(listOf("text", "image"), back.baseModel.inputModalities)
        assertEquals(listOf("text"), back.baseModel.outputModalities)
        assertEquals(ProviderType.openAI.displayName, back.baseModel.provider)
    }


    @Test
    fun `a legacy export restores with nulls, not zeros`() {
        val legacy = JSONObject(
            """{"modelId":"old-model","displayName":"Old Model","isHidden":false,
                "overrides":{"displayName":"Renamed","maxOutputTokens":4096}}""",
        )
        val back = importEntry(legacy, ProviderType.openAI)
        assertEquals("Renamed", back.overrides.displayName)
        assertEquals(4096, back.overrides.maxOutputTokens)
        assertNull(back.overrides.contextWindow)
        assertNull(back.overrides.supportsReasoning)
        assertNull(back.overrides.inputModalities)
        assertNull(back.overrides.outputModalities)
        assertNull("absent temperature must not become 0.0", back.overrides.temperature)
        assertNull(back.overrides.topP)
        assertNull(back.overrides.customHeaders)
        assertNull(back.overrides.extraBodyParams)
        assertNull("absent maxThinkingLevel must stay null", back.overrides.maxThinkingLevel)
    }

    @Test
    fun `an untouched entry carries no overrides object`() {
        val plain = ModelEntry("inst", LLMModel("m", "M", "Custom"))
        val json = exportEntry(plain)
        assertFalse("nothing to write", json.has("overrides"))
        assertTrue(importEntry(JSONObject(json.toString()), ProviderType.openAI).overrides.isEmpty)
    }

    @Test
    fun `a zero temperature survives and does not read as absent`() {
        val back = roundTrip(
            ModelEntry("inst", LLMModel("m", "M", "Custom"), ModelOverrides(temperature = 0.0)),
        )
        assertEquals(0.0, back.overrides.temperature!!, 1e-9)
        assertFalse(back.overrides.isEmpty)
    }

    @Test
    fun `an explicit supportsReasoning false survives as false`() {
        val back = roundTrip(
            ModelEntry("inst", LLMModel("m", "M", "Custom"), ModelOverrides(supportsReasoning = false)),
        )
        assertEquals(false, back.overrides.supportsReasoning)
    }

    @Test
    fun `an empty custom header map round-trips to null`() {
        val back = roundTrip(
            ModelEntry("inst", LLMModel("m", "M", "Custom"), ModelOverrides(customHeaders = emptyMap())),
        )
        assertNull(back.overrides.customHeaders)
    }

    @Test
    fun `nested extra body params keep their structure`() {
        val nested = Json.parseToJsonElement(
            """{"thinking":{"type":"enabled","budget_tokens":8192},"tags":["a","b"]}""",
        ) as JsonObject
        val back = roundTrip(
            ModelEntry("inst", LLMModel("m", "M", "Custom"), ModelOverrides(extraBodyParams = nested)),
        )
        val extra = requireNotNull(back.overrides.extraBodyParams)
        val thinking = extra["thinking"] as JsonObject
        assertEquals("enabled", (thinking["type"] as JsonPrimitive).content)
        assertEquals("8192", (thinking["budget_tokens"] as JsonPrimitive).content)
    }


    @Test
    fun `an iOS bitfield-only export decodes into Android lists`() {
        val bits = bitTextIn or bitTextOut or bitImgIn or bitPdfIn
        val iosShaped = JSONObject(
            """{"modelId":"claude-sonnet-4-6","displayName":"Claude Sonnet 4.6",
                "modalityOverride":$bits,
                "overrides":{"supportsReasoning":true,"modalityOverride":$bits}}""",
        )
        val back = importEntry(iosShaped, ProviderType.anthropic)
        assertEquals(listOf("text", "image", "pdf"), back.baseModel.inputModalities)
        assertEquals(listOf("text"), back.baseModel.outputModalities)
        assertEquals(listOf("text", "image", "pdf"), back.overrides.inputModalities)
        assertEquals(listOf("text"), back.overrides.outputModalities)
    }

    @Test
    fun `an Android export emits both the lists and the interop bitfield`() {
        val entry = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel(
                "m", "M", "Custom",
                inputModalities = listOf("text", "image", "audio"),
                outputModalities = listOf("text", "audio"),
            ),
            overrides = ModelOverrides(
                inputModalities = listOf("text", "image"),
                outputModalities = listOf("text"),
            ),
        )
        val json = exportEntry(entry)
        assertTrue("native lists must be present", json.has("inputModalities"))
        assertTrue("the interop bitfield must be present", json.has("modalityOverride"))
        assertEquals(
            bitTextIn or bitImgIn or bitAudIn or bitTextOut or bitAudOut,
            json.getInt("modalityOverride"),
        )
        val o = json.getJSONObject("overrides")
        assertTrue(o.has("inputModalities"))
        assertEquals(bitTextIn or bitImgIn or bitTextOut, o.getInt("modalityOverride"))
    }

    @Test
    fun `the native lists win over a disagreeing bitfield`() {
        val conflicting = JSONObject(
            """{"modelId":"m","displayName":"M",
                "inputModalities":["text","image","pdf","audio","video"],
                "outputModalities":["text"],
                "modalityOverride":${bitTextIn or bitTextOut}}""",
        )
        val back = importEntry(conflicting, ProviderType.openAI)
        assertEquals(
            listOf("text", "image", "pdf", "audio", "video"),
            back.baseModel.inputModalities,
        )
    }

    @Test
    fun `no modality information at all leaves both sides null`() {
        val back = importEntry(
            JSONObject("""{"modelId":"m","displayName":"M"}"""),
            ProviderType.openAI,
        )
        assertNull(back.baseModel.inputModalities)
        assertNull(back.baseModel.outputModalities)
    }

    @Test
    fun `the bit layout round-trips every modality name`() {
        val ins = listOf("text", "image", "pdf", "audio", "video")
        val outs = listOf("text", "image", "audio", "video")
        val (backIn, backOut) = listsFromBitfield(bitfieldFromLists(ins, outs))
        assertEquals(ins, backIn)
        assertEquals(outs, backOut)
        assertEquals(null to null, listsFromBitfield(0))
        assertEquals(0, bitfieldFromLists(null, listOf("pdf")))
    }


    @Test
    fun `the backup path carries every overrides field including thinking`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val config = ProviderConfig(
            instances = mutableListOf(
                ProviderInstance(
                    id = "inst", label = "Relay",
                    providerType = ProviderType.openAI,
                    credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                ),
            ),
            modelEntries = mutableListOf(
                ModelEntry(
                    providerInstanceId = "inst",
                    baseModel = LLMModel("relay/tuned", "Relay Tuned", "Custom"),
                    overrides = fullOverrides.copy(maxThinkingLevel = ThinkingLevel.XHIGH),
                ),
            ),
        )
        val back = json.decodeFromString(
            ProviderConfig.serializer(),
            json.encodeToString(ProviderConfig.serializer(), config),
        )
        val overrides = back.modelEntries.single().overrides
        assertEquals(fullOverrides.copy(maxThinkingLevel = ThinkingLevel.XHIGH), overrides)
        assertEquals(ThinkingLevel.XHIGH, overrides.maxThinkingLevel)
    }

    @Test
    fun `maxThinkingLevel survives provider export and import`() {
        val original = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel("relay/tuned", "Relay Tuned", "Custom"),
            overrides = ModelOverrides(maxThinkingLevel = ThinkingLevel.XHIGH),
        )
        val exported = exportEntry(original)
        val back = roundTrip(original)

        assertEquals(ThinkingLevel.XHIGH, back.overrides.maxThinkingLevel)
        assertEquals("xhigh", exported.getJSONObject("overrides").getString("maxThinkingLevel"))
        assertFalse(original.overrides.isEmpty)
        assertTrue(exported.has("overrides"))
    }

    @Test
    fun `either casing of the level id is accepted on import`() {
        for (token in listOf("xhigh", "XHIGH", "XHigh")) {
            val back = importEntry(
                JSONObject("""{"modelId":"m","displayName":"M","overrides":{"maxThinkingLevel":"$token"}}"""),
                ProviderType.openAI,
            )
            assertEquals("token \"$token\"", ThinkingLevel.XHIGH, back.overrides.maxThinkingLevel)
        }
        val off = importEntry(
            JSONObject("""{"modelId":"m","displayName":"M","overrides":{"maxThinkingLevel":"off"}}"""),
            ProviderType.openAI,
        )
        assertEquals(ThinkingLevel.OFF, off.overrides.maxThinkingLevel)
    }

    @Test
    fun `an absent or unknown level id imports as null`() {
        val absent = importEntry(
            JSONObject("""{"modelId":"m","displayName":"M","overrides":{"displayName":"X"}}"""),
            ProviderType.openAI,
        )
        assertNull(absent.overrides.maxThinkingLevel)
        val unknown = importEntry(
            JSONObject("""{"modelId":"m","displayName":"M","overrides":{"maxThinkingLevel":"hyper"}}"""),
            ProviderType.openAI,
        )
        assertNull("an unknown level must not be guessed at", unknown.overrides.maxThinkingLevel)
    }


    @Test
    fun `the ported wire format matches the source`() {
        val src = ProductionSources.read("data/repository/ProviderRepository.kt")
        for (key in listOf(
            "displayName", "maxOutputTokens", "contextWindow", "supportsReasoning",
            "inputModalities", "outputModalities", "temperature", "topP",
            "customHeaders", "extraBodyParams", "modalityOverride", "maxThinkingLevel",
        )) {
            assertTrue("export/import must still handle \"$key\"", src.contains("\"$key\""))
        }
        assertTrue(
            "the bitfield fallback must still exist",
            src.contains("readModalitiesWithBitfieldFallback"),
        )
        assertTrue(
            "absent temperature must stay null rather than 0.0",
            src.contains("""if (overridesObj.has("temperature"))"""),
        )
        assertTrue(
            "the ceiling is decoded by the null-on-unknown helper",
            src.contains("private fun thinkingLevelFromWire(raw: String): ThinkingLevel?"),
        )
        assertTrue(src.contains("it.name.equals(raw, ignoreCase = true)"))
        assertTrue(
            "the export writes the cross-platform lowercase level id",
            src.contains("""o.put("maxThinkingLevel", it.name.lowercase())"""),
        )
        assertTrue(src.contains("MODALITY_BIT_TEXT_IN = 1 shl 0"))
        assertTrue(src.contains("MODALITY_BIT_TEXT_OUT = 1 shl 1"))
        assertTrue(src.contains("MODALITY_BIT_IMG_IN = 1 shl 2"))
        assertTrue(src.contains("MODALITY_BIT_PDF_IN = 1 shl 3"))
        assertTrue(src.contains("MODALITY_BIT_VID_OUT = 1 shl 8"))
    }

    @Test
    fun `every ModelOverrides field is named in the export path`() {
        val configSrc = ProductionSources.read("data/model/ProviderConfig.kt")
        val body = configSrc.substringAfter("data class ModelOverrides(")
            .substringBefore("val isEmpty: Boolean")
        val fields = Regex("""^\s*val (\w+):""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("expected to find the overrides fields, got $fields", fields.size >= 10)

        val repoSrc = ProductionSources.read("data/repository/ProviderRepository.kt")
        val missing = fields.filterNot { repoSrc.contains("\"$it\"") }
        assertEquals(
            "a NEW overrides field is missing from export/import — add it there " +
                "(the backup path gets it for free, provider sharing does not): $missing",
            emptyList<String>(),
            missing,
        )
    }
}
