package com.openminis.app.backup

import com.openminis.app.data.model.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object BackupProviderWire {

    private val REQUIRED_FIELDS = mapOf(
        "instances" to listOf("isEnabled", "appendV1Suffix"),
        "modelEntries" to listOf("isHidden", "isCustom"),
        "modelGroups" to listOf("strategy", "fallbackStrategy"),
    )

    private const val ANDROID_DEFAULT_FALLBACK = "default"
    private const val IOS_DEFAULT_FALLBACK = "limited"

    private val withDefaults = Json(BackupFormat.json) { encodeDefaults = true }

    fun encode(config: ProviderConfig): String {
        val slim = BackupFormat.json.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject
        val full = withDefaults.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject

        val out = LinkedHashMap<String, JsonElement>(slim)
        for ((key, fields) in REQUIRED_FIELDS) {
            val fullItems = full[key] as? JsonArray ?: JsonArray(emptyList())
            val slimItems = slim[key] as? JsonArray ?: JsonArray(emptyList())
            out[key] = JsonArray(
                slimItems.mapIndexed { index, item ->
                    val obj = item as? JsonObject ?: return@mapIndexed item
                    val withFields = withFields(obj, fullItems.getOrNull(index) as? JsonObject, fields)
                    if (key == "modelGroups") iosFallback(withFields) else withFields
                },
            )
        }
        if ("sessionBindings" !in out) out["sessionBindings"] = JsonObject(emptyMap())
        return BackupFormat.json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    private fun withFields(obj: JsonObject, full: JsonObject?, fields: List<String>): JsonObject {
        if (full == null || fields.all { it in obj }) return obj
        val merged = LinkedHashMap<String, JsonElement>(obj)
        for (field in fields) {
            if (field !in merged) full[field]?.let { merged[field] = it }
        }
        return JsonObject(merged)
    }

    private fun iosFallback(group: JsonObject): JsonObject {
        val value = (group["fallbackStrategy"] as? JsonPrimitive)?.content
        if (value != ANDROID_DEFAULT_FALLBACK) return group
        return JsonObject(group + ("fallbackStrategy" to JsonPrimitive(IOS_DEFAULT_FALLBACK)))
    }
}
