package com.openminis.app.data

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.logging.AppLogger
import java.io.File

class SessionForkManager(
    private val chatRepository: ChatRepository,
    private val skillRepository: SkillRepository? = null,
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "SessionForkManager"
    }

    suspend fun duplicateSession(sessionId: String): String? {
        val source = chatRepository.getSession(sessionId) ?: run {
            AppLogger.warning(TAG, "duplicateSession: $sessionId not found")
            return null
        }
        val messages = chatRepository.loadMessages(sessionId)
        val dupTitle = "${source.title ?: "Chat"} (Copy)"
        val new = chatRepository.createSession(
            modelId = source.modelId,
            title = dupTitle,
        )
        if (source.category != null) {
            chatRepository.updateSessionTitleAndCategory(new.id, dupTitle, source.category)
        }
        if (source.modelBinding != null) {
            chatRepository.updateSessionBinding(new.id, source.modelBinding, source.modelId)
        }
        if (source.memoryEnabled == 0) {
            chatRepository.dao.updateMemoryEnabled(new.id, 0)
        }
        source.thinkingOverride?.let { override ->
            chatRepository.dao.updateThinkingOverride(new.id, override)
        }
        val oldToNewId = HashMap<String, String>()
        val oldToNewSort = HashMap<String, Int>()
        for (msg in messages) {
            val newMsg = chatRepository.appendMessage(
                sessionId = new.id,
                role = msg.role,
                partsJson = msg.partsJson,
                tokenUsage = com.openminis.app.usage.StoredUsageJson.markCopied(msg.tokenUsage),
                reasoningContent = msg.reasoningContent,
            )
            oldToNewId[msg.id] = newMsg.id
            oldToNewSort[msg.id] = newMsg.sortOrder
        }

        val markers = chatRepository.dao.listCompactMarkers(sessionId)
        if (markers.isNotEmpty()) {
            fun remapId(id: String?): String? = id?.let { oldToNewId[it] }
            fun remapSort(messageId: String?, original: Int): Int =
                messageId?.let { oldToNewSort[it] } ?: original
            var copied = 0
            for (marker in markers) {
                val firstKeptNew = remapId(marker.firstKeptMessageId)
                val lastCompactedNew = remapId(marker.lastCompactedMessageId)
                val boundaryNew = remapId(marker.boundaryMessageId)
                if ((marker.firstKeptMessageId != null && firstKeptNew == null) ||
                    (marker.lastCompactedMessageId != null && lastCompactedNew == null) ||
                    (marker.boundaryMessageId != null && boundaryNew == null)
                ) {
                    AppLogger.warning(
                        TAG,
                        "duplicateSession: skipping compact marker ${marker.id} — a referenced message id is not in the copy map (dangling source marker)",
                    )
                    continue
                }
                val copy = marker.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = new.id,
                    firstKeptSortOrder = remapSort(marker.firstKeptMessageId, marker.firstKeptSortOrder),
                    uiBoundarySortOrder = marker.uiBoundarySortOrder?.let {
                        remapSort(marker.boundaryMessageId, it)
                    },
                    boundaryMessageId = boundaryNew,
                    firstKeptMessageId = firstKeptNew,
                    lastCompactedMessageId = lastCompactedNew,
                )
                chatRepository.dao.insertCompactMarker(copy)
                copied++
            }
            AppLogger.info(TAG, "duplicateSession: copied $copied/${markers.size} compact marker(s) to ${new.id}")
        }

        AppLogger.info(
            TAG,
            "duplicated session $sessionId → ${new.id} (${messages.size} msgs, " +
                "category=${source.category}, modelBinding=${source.modelBinding}, " +
                "memoryEnabled=${source.memoryEnabled})",
        )
        return new.id
    }

    fun copySkill(content: String, source: SkillRepository.ImportSource = SkillRepository.ImportSource.FILE): Boolean {
        val repo = skillRepository ?: run {
            AppLogger.warning(TAG, "copySkill: SkillRepository not injected")
            return false
        }
        return repo.importFromContent(content, source) != null
    }

    fun copyMemory(fileName: String, content: String): Boolean {
        if (fileName.contains("/") || fileName.contains("..")) {
            AppLogger.warning(TAG, "copyMemory: rejecting unsafe fileName '$fileName'")
            return false
        }
        val dir = File(filesDir, "minis-global/memory").apply { mkdirs() }
        return try {
            File(dir, fileName).writeText(content)
            true
        } catch (e: Exception) {
            AppLogger.warning(TAG, "copyMemory: write failed: ${e.message}")
            false
        }
    }
}

fun SessionForkManager(
    context: Context,
    chatRepository: ChatRepository,
    skillRepository: SkillRepository? = null,
): SessionForkManager = SessionForkManager(
    chatRepository = chatRepository,
    skillRepository = skillRepository,
    filesDir = context.filesDir,
)
