package com.openminis.app.provider.xai

import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi

object XAIModelsApi {
    private const val TAG = "XAIModelsApi"

    fun fetchModelsOAuth(): List<LLMModel> {
        val models = LLMModel.allXAI
        AppLogger.info(TAG, "xAI OAuth model list (${models.size} models): ${models.joinToString { it.id }}")
        return ModelsDevApi.enrichModels(models)
    }
}
