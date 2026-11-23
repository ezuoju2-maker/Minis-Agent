package com.openminis.app.auth

import org.json.JSONObject

object CopilotDeviceFlow {


    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

    const val SCOPE = "read:user"

    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"


    const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"


    const val API_BASE = "https://api.githubcopilot.com"

    const val EDITOR_VERSION = "vscode/1.104.0"
    const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.31.0"
    const val USER_AGENT = "GitHubCopilotChat/0.31.0"
    const val API_VERSION = "2025-04-01"
    const val INTEGRATION_ID = "vscode-chat"

    fun tokenExchangeHeaders(githubToken: String): Map<String, String> = mapOf(
        "Authorization" to "token $githubToken",
        "User-Agent" to USER_AGENT,
        "Editor-Version" to EDITOR_VERSION,
        "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
        "X-GitHub-Api-Version" to API_VERSION,
        "Accept" to "application/json",
    )

    fun chatHeaders(
        isAgentInitiated: Boolean,
        hasImages: Boolean,
        requestId: String,
    ): Map<String, String> = buildMap {
        put("Copilot-Integration-Id", INTEGRATION_ID)
        put("Editor-Version", EDITOR_VERSION)
        put("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
        put("User-Agent", USER_AGENT)
        put("Openai-Intent", "conversation-panel")
        put("X-GitHub-Api-Version", API_VERSION)
        put("X-Initiator", if (isAgentInitiated) "agent" else "user")
        put("X-Request-Id", requestId)
        if (hasImages) put("Copilot-Vision-Request", "true")
    }

    fun staticChatHeaders(): Map<String, String> = mapOf(
        "Copilot-Integration-Id" to INTEGRATION_ID,
        "Editor-Version" to EDITOR_VERSION,
        "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
        "User-Agent" to USER_AGENT,
        "Openai-Intent" to "conversation-panel",
        "X-GitHub-Api-Version" to API_VERSION,
    )

    fun perRequestHeaders(body: JSONObject): Map<String, String> {
        val messages = body.optJSONArray("messages")
            ?: body.optJSONArray("input")

        var isAgentInitiated = false
        var hasImages = false

        if (messages != null && messages.length() > 0) {
            isAgentInitiated =
                messages.optJSONObject(messages.length() - 1)?.optString("role") == "tool"

            outer@ for (i in 0 until messages.length()) {
                val parts = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) {
                    val type = parts.optJSONObject(j)?.optString("type")
                    if (type == "image_url" || type == "input_image") {
                        hasImages = true
                        break@outer
                    }
                }
            }
        }

        return buildMap {
            put("X-Initiator", if (isAgentInitiated) "agent" else "user")
            put("X-Request-Id", java.util.UUID.randomUUID().toString())
            if (hasImages) put("Copilot-Vision-Request", "true")
        }
    }


    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    )

    fun parseDeviceAuthorization(json: JSONObject): DeviceAuthorization? {
        val deviceCode = json.optString("device_code", "").ifEmpty { return null }
        val userCode = json.optString("user_code", "").ifEmpty { return null }
        val uri = json.optString("verification_uri", "")
            .ifEmpty { json.optString("verification_url", "") }
            .ifEmpty { return null }
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 } ?: 900L,
            intervalSeconds = json.optLong("interval", 0).takeIf { it > 0 } ?: 5L,
        )
    }

    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        data class Denied(val description: String) : PollResult()
        data class Expired(val description: String) : PollResult()
        data class Fatal(val description: String) : PollResult()
    }

    fun classifyPoll(json: JSONObject, httpOK: Boolean): PollResult {
        if (httpOK) {
            val access = json.optString("access_token", "")
            if (access.isNotEmpty()) return PollResult.Success(access)
        }
        val error = json.optString("error", "").lowercase().ifEmpty { "unknown_error" }
        val description = json.optString("error_description", "").ifEmpty { error }
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Denied(description)
            "expired_token" -> PollResult.Expired(description)
            else -> PollResult.Fatal(description)
        }
    }

    fun bumpedInterval(currentSeconds: Long): Long = currentSeconds + 5


    data class SessionToken(
        val token: String,
        val expiresAtEpochSeconds: Long,
    ) {
        fun needsRefresh(nowEpochSeconds: Long): Boolean =
            nowEpochSeconds >= (expiresAtEpochSeconds - 60)
    }

    fun parseSessionToken(json: JSONObject, nowEpochSeconds: Long): SessionToken? {
        val token = json.optString("token", "").ifEmpty { return null }
        val expiresAt = json.optLong("expires_at", 0)
        if (expiresAt > 0) return SessionToken(token, expiresAt)
        val refreshIn = json.optLong("refresh_in", 0)
        if (refreshIn > 0) return SessionToken(token, nowEpochSeconds + refreshIn)
        return null
    }

    fun parseModelIds(json: JSONObject): List<Pair<String, String>> =
        parseModels(json).map { it.id to it.displayName }

    data class CopilotModel(
        val id: String,
        val displayName: String,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
        val supportsVision: Boolean,
        val supportsReasoning: Boolean?,
        val reasoningEffortValues: List<String>?,
    )

    data class ParsedModels(
        val models: List<CopilotModel>,
        val hiddenByPolicy: List<String>,
        val droppedByType: List<String>,
        val totalReturned: Int,
    )

    fun parseModels(json: JSONObject): List<CopilotModel> = parseModelsDetailed(json).models

    fun parseModelsDetailed(json: JSONObject): ParsedModels {
        val data = json.optJSONArray("data")
            ?: return ParsedModels(emptyList(), emptyList(), emptyList(), 0)
        val out = mutableListOf<CopilotModel>()
        val hidden = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue
            if (!m.optBoolean("model_picker_enabled", true)) {
                hidden.add(id)
                continue
            }
            val caps = m.optJSONObject("capabilities")
            val type = caps?.optString("type", "").orEmpty()
            if (type.isNotEmpty() && type != "chat") {
                dropped.add("$id:$type")
                continue
            }
            if (!seen.add(id)) continue
            val limits = caps?.optJSONObject("limits")
            val supports = caps?.optJSONObject("supports")
            out.add(
                CopilotModel(
                    id = id,
                    displayName = m.optString("name", "").ifEmpty { id },
                    contextWindow = limits?.optInt("max_context_window_tokens", 0)
                        ?.takeIf { it > 0 },
                    maxOutputTokens = limits?.optInt("max_output_tokens", 0)
                        ?.takeIf { it > 0 },
                    supportsVision = supports?.optBoolean("vision", false) ?: false,
                    supportsReasoning = when {
                        supports == null -> null
                        supports.optBoolean("adaptive_thinking", false) -> true
                        supports.optJSONArray("reasoning_effort")?.length()?.let { it > 0 } == true -> true
                        supports.has("thinking") -> supports.optBoolean("thinking", false)
                        else -> false
                    },
                    reasoningEffortValues = supports?.optJSONArray("reasoning_effort")
                        ?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i).takeIf { it.isNotEmpty() }
                            }.takeIf { it.isNotEmpty() }
                        },
                ),
            )
        }
        return ParsedModels(out, hidden, dropped, data.length())
    }
}
