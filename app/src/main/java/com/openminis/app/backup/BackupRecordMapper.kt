package com.openminis.app.backup

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object BackupRecordMapper {


    fun incomingWins(existingUpdatedAt: Long?, incomingUpdatedAt: Long): Boolean =
        existingUpdatedAt == null || incomingUpdatedAt > existingUpdatedAt


    sealed class Decoded<out T> {
        object Unreadable : Decoded<Nothing>()
        data class Stale(val id: String) : Decoded<Nothing>()
        data class Apply<T>(val id: String, val entity: T, val isNew: Boolean) : Decoded<T>()
    }

    fun session(payload: JsonObject, existing: ChatSessionEntity?): Decoded<ChatSessionEntity> {
        val s = payload.unwrapNested("session")
        val id = s.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = s.millis("updatedAt") ?: 0
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            ChatSessionEntity(
                id = id,
                title = s.str("title"),
                modelId = s.str("modelId") ?: existing?.modelId ?: "",
                createdAt = s.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
                category = s.str("category"),
                lastMessage = s.str("lastMessage"),
                modelBinding = s.str("modelBinding"),
                source = s.str("source"),
                memoryEnabled = if (s.bool("memoryEnabled") != false) 1 else 0,
                pinnedAt = s.millis("pinnedAt"),
                editCount = s.int("editCount") ?: 0,
                thinkingOverride = s.str("thinkingOverride"),
                folderId = s.str("folderId"),
                parentSessionId = s.str("parentSessionId"),
                parentToolUseId = s.str("parentToolUseId"),
            ),
            isNew = existing == null,
        )
    }


    fun folder(f: JsonObject, existing: FolderEntity?): Decoded<FolderEntity> {
        val id = f.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = f.millis("updatedAt") ?: 0
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            FolderEntity(
                id = id,
                name = f.str("name") ?: "",
                icon = f.str("icon"),
                color = f.str("color"),
                origin = f.str("origin") ?: FolderEntity.ORIGIN_MANUAL,
                sortIndex = f.int("sortIndex") ?: 0,
                pinnedAt = f.millis("pinnedAt"),
                description = f.str("description") ?: f.str("desc"),
                createdAt = f.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
            ),
            isNew = existing == null,
        )
    }


    fun message(m: JsonObject): MessageEntity? {
        val id = m.str("id") ?: return null
        val sessionId = m.str("sessionId") ?: return null
        val createdAt = m.millis("createdAt") ?: 0
        return MessageEntity(
            id = id,
            sessionId = sessionId,
            role = m.str("role") ?: "user",
            partsJson = (m["parts"]?.toString()) ?: "[]",
            createdAt = createdAt,
            tokenUsage = m["tokenUsage"]?.takeIf { it.toString() != "null" }?.toString(),
            sortOrder = m.int("sortOrder") ?: 0,
            reasoningContent = m.str("reasoningContent"),
            streamInterruptCount = m.int("streamInterruptCount") ?: 0,
            updatedAt = createdAt,
            errorInfo = null,
            modelId = m.str("modelId"),
            modelDisplayName = m.str("modelDisplayName"),
            providerType = m.str("providerType"),
            providerInstanceId = m.str("providerInstanceId"),
        )
    }


    fun compactMarker(c: JsonObject): CompactMarkerEntity? {
        val id = c.str("id") ?: return null
        val sessionId = c.str("sessionId") ?: return null
        return CompactMarkerEntity(
            id = id,
            sessionId = sessionId,
            summary = c.str("summary") ?: "",
            firstKeptSortOrder = c.int("firstKeptSortOrder") ?: 0,
            compactedCount = c.int("compactedCount") ?: 0,
            createdAt = c.millis("createdAt") ?: 0,
            uiBoundarySortOrder = c.int("uiBoundarySortOrder"),
            boundaryMessageId = c.str("boundaryMessageId"),
            firstKeptMessageId = c.str("firstKeptMessageId"),
            lastCompactedMessageId = c.str("lastCompactedMessageId"),
            version = c.int("version") ?: 1,
        )
    }


    fun JsonObject.unwrapNested(key: String): JsonObject {
        val inner = (this[key] as? JsonObject) ?: return this
        return JsonObject(this.filterKeys { it != key } + inner)
    }

    fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it.toString() != "null" }?.runCatching { jsonPrimitive.content }
            ?.getOrNull()

    fun JsonObject.int(key: String): Int? =
        this[key]?.runCatching { jsonPrimitive.content.toInt() }?.getOrNull()

    fun JsonObject.bool(key: String): Boolean? =
        this[key]?.runCatching { jsonPrimitive.content.toBooleanStrict() }?.getOrNull()

    fun JsonObject.millis(key: String): Long? {
        val raw = str(key) ?: return null
        raw.toLongOrNull()?.let { return it }
        for (pattern in ISO_PATTERNS) {
            runCatching {
                val f = SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                return f.parse(raw)?.time
            }
        }
        return null
    }

    private val ISO_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )
}
