package com.openminis.app.debug

import android.content.Context
import androidx.core.content.FileProvider
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.InputAttachment
import org.json.JSONObject
import java.io.File

internal object ChatMutationMethods {

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")

    suspend fun uiPrompt(context: Context, params: JSONObject): JSONObject {
        val text = params.optString("prompt", "").ifEmpty {
            throw RPCException(-32602, "Missing 'prompt' param")
        }
        val app = app(context)
        val store = com.openminis.app.ui.chat.ChatViewModelStore
        val sessionId = params.optString("sessionId", "").ifEmpty { null }
            ?: store.activeSessionId
            ?: throw RPCException(-32000, "No chat is on screen (activeSessionId is null) and no sessionId was given")
        val vm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            androidx.lifecycle.ViewModelProvider(
                store.ownerFor(sessionId),
                com.openminis.app.ui.chat.ChatViewModel.factory(
                    sessionId = sessionId,
                    chatRepository = app.chatRepository,
                    providerRepository = app.providerRepository,
                    appContext = app.applicationContext,
                    memoryRepository = app.memoryRepository,
                    skillRepository = app.skillRepository,
                    mcpRepository = app.mcpRepository,
                ),
            )[com.openminis.app.ui.chat.ChatViewModel::class.java].also { it.sendMessage(text) }
        }
        return JSONObject().apply {
            put("ok", true)
            put("sessionId", sessionId)
            put("isStreaming", vm.isStreaming.value)
        }
    }

    suspend fun prompt(context: Context, params: JSONObject): JSONObject {
        val text = params.optString("prompt", "").ifEmpty {
            throw RPCException(-32602, "Missing 'prompt' param")
        }
        val app = app(context)
        val existingId = params.optString("sessionId", "").ifEmpty { null }
        val isNew = existingId == null
        val sessionId = existingId ?: HeadlessChatRunner.ensureSession(context)

        val modelEntryId = if (params.has("modelEntryId") && !params.isNull("modelEntryId")) params.optString("modelEntryId").ifEmpty { null } else null
        val modelGroupId = if (params.has("modelGroupId") && !params.isNull("modelGroupId")) params.optString("modelGroupId").ifEmpty { null } else null
        val overrideName = HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, modelGroupId)

        val attachments = parseAttachments(context, params)

        val wait = params.optBoolean("wait", false)
        val timeoutSec = params.optInt("waitTimeout", 600).coerceIn(1, 1800)
        val thinkingLevel = parseThinkingLevel(params)
        val result = HeadlessChatRunner.prompt(
            context = context,
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            thinkingLevel = thinkingLevel,
            wait = wait,
            timeoutMs = timeoutSec * 1000L,
        )

        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        val userMsg = msgs.lastOrNull { it.role == "user" }
        val displayedModelName = overrideName ?: app.chatRepository.dao.getSession(sessionId)?.let { resolveDisplay(context, it.modelId) }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("isNewSession", isNew)
            put("modelName", displayedModelName ?: JSONObject.NULL)
            put("status", result.status)
            put("prompt", text)
            put("responseText", result.responseText ?: JSONObject.NULL)
            put("userMessageId", userMsg?.id ?: JSONObject.NULL)
            if (result.timedOut) put("timedOut", true)
        }
    }

    suspend fun retry(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val messageId = if (params.has("messageId") && !params.isNull("messageId")) params.optString("messageId").ifEmpty { null } else null

        val modelEntryId = if (params.has("modelEntryId") && !params.isNull("modelEntryId")) params.optString("modelEntryId").ifEmpty { null } else null
        val modelGroupId = if (params.has("modelGroupId") && !params.isNull("modelGroupId")) params.optString("modelGroupId").ifEmpty { null } else null
        val overrideName = HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, modelGroupId)

        val wait = params.optBoolean("wait", false)
        val timeoutSec = params.optInt("waitTimeout", 600).coerceIn(1, 1800)
        val result = HeadlessChatRunner.retry(
            context = context,
            sessionId = sessionId,
            messageId = messageId,
            wait = wait,
            timeoutMs = timeoutSec * 1000L,
        )
        val displayedModelName = overrideName
            ?: app.chatRepository.dao.getSession(sessionId)?.let { resolveDisplay(context, it.modelId) }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("status", result.status)
            put("retriedMessageId", result.retriedMessageId ?: JSONObject.NULL)
            put("deletedMessageCount", result.deletedMessageCount)
            put("modelName", displayedModelName ?: JSONObject.NULL)
            put("responseText", result.responseText ?: JSONObject.NULL)
            if (result.timedOut) put("timedOut", true)
        }
    }

    suspend fun rerunFromToolBlock(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val assistantMessageId = params.optString("assistantMessageId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'assistantMessageId' param")
        }
        val blockId = params.optString("blockId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'blockId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val wait = params.optBoolean("wait", false)
        val timeoutSec = params.optInt("waitTimeout", 600).coerceIn(1, 1800)
        val result = HeadlessChatRunner.rerunFromToolBlock(
            context = context,
            sessionId = sessionId,
            assistantMessageId = assistantMessageId,
            blockId = blockId,
            wait = wait,
            timeoutMs = timeoutSec * 1000L,
        )
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("status", result.status)
            put("deletedMessageCount", result.deletedMessageCount)
            put("responseText", result.responseText ?: JSONObject.NULL)
            if (result.timedOut) put("timedOut", true)
        }
    }

    suspend fun status(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        val s = app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        val lastMsg = msgs.lastOrNull()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("title", s.title ?: JSONObject.NULL)
            put("modelName", resolveDisplay(context, s.modelId))
            put("isRunning", com.openminis.app.service.SessionActivityTracker.isProcessing(sessionId))
            put("isStreaming", com.openminis.app.service.SessionActivityTracker.isActive(sessionId))
            put("messageCount", msgs.size)
            put("lastMessageRole", lastMsg?.role ?: JSONObject.NULL)
            put("updatedAt", s.updatedAt)
        }
    }

    suspend fun cancel(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val wasRunning = HeadlessChatRunner.cancel(context, sessionId)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("wasRunning", wasRunning)
            put("cancelled", wasRunning)
        }
    }

    suspend fun selectModel(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val entryId = params.optString("modelEntryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'modelEntryId' param")
        }
        val (modelName, thinkingLevel) = HeadlessChatRunner.selectModel(context, sessionId, entryId)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("modelEntryId", entryId)
            put("modelName", modelName)
            put("thinkingLevel", thinkingLevel)
        }
    }

    suspend fun compactBefore(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val includesBoundary = params.optBoolean("includesBoundary", false)
        val messageId = if (params.has("messageId") && !params.isNull("messageId"))
            params.optString("messageId").ifEmpty { null } else null
        if (!includesBoundary && messageId == null) {
            throw RPCException(-32602, "Missing 'messageId' param (required when includesBoundary=false)")
        }
        val waitTimeoutSec = params.optInt("waitTimeout", 120).coerceIn(1, 1800)
        val beforeMarkerCount = app.chatRepository.dao.listCompactMarkers(sessionId).size
        val result = HeadlessChatRunner.compact(
            context = context,
            sessionId = sessionId,
            wait = true,
            timeoutMs = waitTimeoutSec * 1000L,
            messageId = messageId,
            includesBoundary = includesBoundary,
        )
        val afterMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val latest = afterMarkers.maxByOrNull { it.createdAt }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("messageId", messageId ?: JSONObject.NULL)
            put("includesBoundary", includesBoundary)
            put("beforeMarkerCount", beforeMarkerCount)
            put("afterMarkerCount", afterMarkers.size)
            put("wrote", afterMarkers.size > beforeMarkerCount)
            put("status", result.status)
            if (result.timedOut) put("timedOut", true)
            if (result.error != null) put("error", result.error)
            put("latestMarker", latest?.let { m ->
                JSONObject().apply {
                    put("id", m.id)
                    put("version", m.version)
                    put("anchorMessageId", m.lastCompactedMessageId ?: JSONObject.NULL)
                    put("summaryLength", m.summary.length)
                    put("compactedCount", m.compactedCount)
                    put("createdAt", m.createdAt)
                }
            } ?: JSONObject.NULL)
        }
    }

    suspend fun compactMarkersList(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val includeFullSummary = params.optBoolean("includeFullSummary", false)
        val markers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val arr = org.json.JSONArray()
        for (m in markers) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("version", m.version)
                put("anchorMessageId", m.lastCompactedMessageId ?: JSONObject.NULL)
                put("firstKeptMessageId", m.firstKeptMessageId ?: JSONObject.NULL)
                put("summaryLength", m.summary.length)
                put("summaryPreview", m.summary.take(120))
                put("compactedCount", m.compactedCount)
                put("createdAt", m.createdAt)
                if (includeFullSummary) put("summary", m.summary)
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("count", arr.length())
            put("markers", arr)
        }
    }

    suspend fun compactRevert(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val beforeMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val removed = beforeMarkers.maxByOrNull { it.createdAt }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            HeadlessChatRunner.revertCompact(context, sessionId)
        }
        var afterMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val deadline = System.currentTimeMillis() + 5000L
        while (afterMarkers.size == beforeMarkers.size && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(150L)
            afterMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        }
        val newLatest = afterMarkers.maxByOrNull { it.createdAt }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("beforeMarkerCount", beforeMarkers.size)
            put("afterMarkerCount", afterMarkers.size)
            put("removedMarkerId",
                if (afterMarkers.size < beforeMarkers.size) (removed?.id ?: JSONObject.NULL) else JSONObject.NULL)
            put("newLatestMarkerId", newLatest?.id ?: JSONObject.NULL)
        }
    }

    suspend fun delete(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete a session")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val report = com.openminis.app.data.session.SessionDeleter.deleteTree(
            context, app.chatRepository, sessionId, "rpc",
        )
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("deleted", true)
            put("deletedSessionIds", org.json.JSONArray(report.deletedIds))
            put("bytesFreed", report.bytesFreed)
            put("jobsCancelled", report.jobsCancelled)
        }
    }

    suspend fun runScheduledAgentChild(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty { throw RPCException(-32602, "Missing 'sessionId' param") }
        val prompt = params.optString("prompt", "").ifEmpty { throw RPCException(-32602, "Missing 'prompt' param") }
        val label = params.optString("label", "Agent child probe")
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val cal = java.util.Calendar.getInstance()
        val task = com.openminis.app.scheduled.ScheduledTask(
            label = label,
            timeOfDayHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            timeOfDayMinute = cal.get(java.util.Calendar.MINUTE),
            repeatMode = com.openminis.app.scheduled.ScheduledRepeatMode.ONCE,
            prompt = prompt,
            targetMode = com.openminis.app.scheduled.ScheduledTargetMode.ChildOfCurrent(sessionId),
            enabled = false,
        )
        com.openminis.app.scheduled.ScheduledTaskManager(app).create(task)
        val childId = com.openminis.app.scheduled.ScheduledAgentRunner.run(app, task, waitForCompletion = false)
        return JSONObject().apply {
            put("taskId", task.id)
            put("childSessionId", childId ?: JSONObject.NULL)
        }
    }

    private fun parseAttachments(context: Context, params: JSONObject): List<InputAttachment> {
        val arr = params.optJSONArray("attachments") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val out = mutableListOf<InputAttachment>()
        val cacheDir = File(context.cacheDir, "rpc-attachments").also { it.mkdirs() }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").ifEmpty { "attachment-$i" }
            val data = obj.optString("data", "")
            if (data.isEmpty()) continue
            val mime = obj.optString("mime", "").ifEmpty { guessMime(name) }
            val bytes = try {
                android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            } catch (_: Exception) { null } ?: continue
            val file = File(cacheDir, "${System.currentTimeMillis()}-$name")
            file.outputStream().use { it.write(bytes) }
            val authority = context.packageName + ".fileprovider"
            val uri = try {
                FileProvider.getUriForFile(context, authority, file)
            } catch (_: Exception) {
                android.net.Uri.fromFile(file)
            }
            val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
                else InputAttachment.Kind.DOCUMENT
            out.add(InputAttachment(fileName = name, uri = uri, mimeType = mime, kind = kind))
        }
        return out
    }

    private fun parseThinkingLevel(params: JSONObject): ThinkingLevel? {
        if (!params.has("thinkingLevel") || params.isNull("thinkingLevel")) return null
        val raw = params.optString("thinkingLevel", "").trim().lowercase()
        if (raw.isEmpty()) return null
        return when (raw) {
            "off" -> ThinkingLevel.OFF
            "low" -> ThinkingLevel.LOW
            "medium" -> ThinkingLevel.MEDIUM
            "high" -> ThinkingLevel.HIGH
            "xhigh" -> ThinkingLevel.XHIGH
            "max" -> ThinkingLevel.MAX
            "ultra" -> ThinkingLevel.ULTRA
            else -> throw RPCException(-32602, "Invalid thinkingLevel: $raw (expected off/low/medium/high/xhigh/max/ultra)")
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun resolveDisplay(context: Context, modelId: String): String {
        val cfg = app(context).providerRepository.config.value
        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId } ?: return modelId
        return entry.model.displayName
    }
}
