package com.openminis.app.offload

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import org.json.JSONArray
import org.json.JSONObject

object ModelUseManager {

    private const val NO_MODELS_HINT =
        "No models available. Go to Settings > Model Groups > Available Models in Agent Loop to expose models to the agent."

    fun listModels(
        entries: List<ModelEntry>,
        instances: Map<String, ProviderInstance> = emptyMap(),
        filter: String? = null,
    ): String {
        val filtered = if (filter.isNullOrBlank()) entries else {
            val q = filter.lowercase()
            entries.filter {
                it.model.id.lowercase().contains(q) ||
                    it.model.displayName.lowercase().contains(q) ||
                    it.model.provider.lowercase().contains(q)
            }
        }

        if (filtered.isEmpty()) {
            return if (filter != null) "No models matching '$filter'."
            else NO_MODELS_HINT
        }

        val result = JSONArray()
        for (entry in filtered) {
            result.put(entryDict(entry, instances[entry.providerInstanceId]))
        }
        return result.toString(2)
    }

    fun entryDict(entry: ModelEntry, instance: ProviderInstance?): JSONObject {
        val enrichedModel = com.openminis.app.provider.ModelsDevApi.enrichModel(entry.model)
        val modalities = JSONArray()
        val inputs = enrichedModel.inputModalities.orEmpty()
        val outputs = enrichedModel.outputModalities.orEmpty()
        val inputSet = if (inputs.isEmpty() && outputs.isEmpty()) listOf("text") else inputs
        val outputSet = if (inputs.isEmpty() && outputs.isEmpty()) listOf("text") else outputs
        if ("text" in inputSet) modalities.put("text_input")
        if ("text" in outputSet) modalities.put("text_output")
        if ("image" in inputSet) modalities.put("image_input")
        if ("image" in outputSet) modalities.put("image_output")
        if ("audio" in inputSet) modalities.put("audio_input")
        if ("audio" in outputSet) modalities.put("audio_output")
        if ("video" in inputSet) modalities.put("video_input")
        if ("video" in outputSet) modalities.put("video_output")
        if ("pdf" in inputSet) modalities.put("pdf_input")

        return JSONObject().apply {
            put("entry_id", entry.id)
            put("model_id", entry.model.id)
            put("display_name", entry.model.displayName)
            put("provider", entry.model.provider)
            put("instance_label", instance?.label ?: "unknown")
            put("provider_type", instance?.providerType?.displayName ?: "unknown")
            put("modalities", modalities)
            (entry.model.contextWindow ?: enrichedModel.contextWindow)?.let { put("context_window", it) }
        }
    }
}
