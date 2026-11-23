package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class SessionMetaRow(
    val id: String,
    val title: String?,
    @ColumnInfo(name = "first_user_msg") val firstUserMsg: String?,
    val source: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "msg_count") val msgCount: Int,
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    val category: String? = null,
)

data class MessageSearchRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val id: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

data class SessionTailRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

data class MessageHeadRow(
    val id: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

data class SessionTitleRow(val title: String?, val category: String?)

@Dao
interface ChatDao {
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    suspend fun listSessions(): List<ChatSessionEntity>

    @Query(
        "SELECT * FROM sessions WHERE parent_session_id IS NULL AND id != :excludeId " +
            "ORDER BY updated_at DESC LIMIT :limit",
    )
    suspend fun listRecentMovableSessions(
        excludeId: String,
        limit: Int = 50,
    ): List<ChatSessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("SELECT title, category FROM sessions WHERE id = :id")
    fun observeSessionTitle(id: String): Flow<SessionTitleRow?>

    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET title = :title, category = COALESCE(:category, category), updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionTitleAndCategory(id: String, title: String, category: String?, updatedAt: Long)

    @Query("UPDATE sessions SET category = :category, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionCategory(id: String, category: String?, updatedAt: Long)

    @Query("UPDATE sessions SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: String, updatedAt: Long)

    @Query("UPDATE sessions SET last_message = :preview, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLastMessage(id: String, preview: String?, updatedAt: Long)

    @Query("UPDATE sessions SET model_id = :modelId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionModel(id: String, modelId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET model_binding = :binding, model_id = :modelId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionBinding(id: String, binding: String, modelId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sessions WHERE model_binding LIKE '%' || :needle || '%'")
    suspend fun sessionsWithBindingLike(needle: String): List<ChatSessionEntity>

    @Query("UPDATE sessions SET model_binding = :binding WHERE id = :id")
    suspend fun updateSessionBindingOnly(id: String, binding: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT id FROM sessions WHERE parent_session_id = :parentId")
    suspend fun childSessionIds(parentId: String): List<String>

    @Query("SELECT COUNT(*) FROM sessions WHERE parent_session_id IS NOT NULL")
    suspend fun childSessionCount(): Int

    @Query(
        """
        SELECT id FROM sessions c
        WHERE c.parent_session_id IS NOT NULL
          AND c.parent_session_id != ''
          AND c.updated_at < :olderThanMs
          AND NOT EXISTS (SELECT 1 FROM sessions p WHERE p.id = c.parent_session_id)
        """
    )
    suspend fun orphanChildSessionIds(olderThanMs: Long): List<String>

    @RawQuery
    suspend fun runSessionSearchQuery(query: SupportSQLiteQuery): List<SessionSearchRow>

    @RawQuery
    suspend fun runPartsJsonQuery(query: SupportSQLiteQuery): List<PartsJsonRow>


    @Query("SELECT * FROM folders WHERE id != '' ORDER BY updated_at DESC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id != '' ORDER BY updated_at DESC")
    suspend fun listFolders(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolder(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Query(
        """
        UPDATE folders
        SET name = :name,
            description = COALESCE(:description, description),
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun renameFolder(id: String, name: String, description: String?, updatedAt: Long)

    @Query("UPDATE folders SET pinned_at = :pinnedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setFolderPinned(id: String, pinnedAt: Long?, updatedAt: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("SELECT id FROM sessions WHERE folder_id = :folderId ORDER BY updated_at DESC")
    suspend fun sessionIdsInFolder(folderId: String): List<String>

    @Query(
        "SELECT folder_id AS folderId, title, updated_at AS updatedAt FROM sessions " +
            "WHERE folder_id IS NOT NULL AND folder_id != '' " +
            "AND title IS NOT NULL AND TRIM(title) != '' " +
            "AND (parent_session_id IS NULL OR parent_session_id = '') " +
            "ORDER BY folder_id, updated_at DESC",
    )
    suspend fun titledFolderMembers(): List<FolderMemberTitle>

    @Query("SELECT COUNT(*) FROM sessions WHERE folder_id = :folderId")
    suspend fun sessionCountInFolder(folderId: String): Int

    @Query("UPDATE sessions SET folder_id = :folderId WHERE id = :sessionId")
    suspend fun setSessionFolder(sessionId: String, folderId: String?)

    @Query("UPDATE sessions SET folder_id = :folderId WHERE id = :sessionId AND folder_id IS NULL")
    suspend fun setSessionFolderIfUnfiled(sessionId: String, folderId: String): Int

    @Query("UPDATE sessions SET folder_id = NULL WHERE folder_id = :folderId")
    suspend fun clearFolderForSessions(folderId: String)

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY sort_order ASC")
    suspend fun loadMessages(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY sort_order ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT MIN(created_at) FROM messages WHERE id IN (:ids)")
    suspend fun earliestCreatedAt(ids: List<String>): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query(
        "SELECT * FROM messages WHERE role = 'user' AND created_at > :since " +
            "ORDER BY created_at ASC LIMIT :limit",
    )
    suspend fun loadUserMessagesSince(since: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM messages WHERE session_id = :sessionId")
    suspend fun nextSortOrder(sessionId: String): Int

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND sort_order >= :keepCount")
    suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun totalMessageCount(): Int

    @Query("SELECT token_usage FROM messages WHERE session_id = :sessionId AND token_usage IS NOT NULL ORDER BY sort_order ASC")
    suspend fun tokenUsages(sessionId: String): List<String>

    @Query("""
        SELECT COALESCE(m.model_id, s.model_id) AS modelId,
               m.model_display_name  AS modelDisplayName,
               m.provider_type       AS providerType,
               (m.model_id IS NOT NULL) AS hasSnapshot,
               m.token_usage AS tokenUsage, m.created_at AS createdAt, m.session_id AS sessionId
        FROM messages m LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.token_usage IS NOT NULL
    """)
    suspend fun allUsageRecords(): List<UsageRecord>

    @Query("""
        SELECT m.created_at AS createdAt, m.session_id AS sessionId,
               m.model_id AS modelId, s.model_id AS sessionModelId,
               m.model_display_name AS modelDisplayName, m.provider_type AS providerType,
               m.provider_instance_id AS providerInstanceId, m.token_usage AS tokenUsage
        FROM messages m LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.token_usage IS NOT NULL AND m.created_at >= :sinceMs
    """)
    suspend fun usageRowsSince(sinceMs: Long): List<UsageRowRecord>

    @Query("""
        SELECT parts_json FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order DESC LIMIT 1
    """)
    suspend fun lastMessageParts(sessionId: String): String?

    @Query("""
        SELECT m.session_id AS session_id, m.role AS role, m.parts_json AS parts_json
        FROM messages m
        WHERE m.sort_order = (
            SELECT MAX(m2.sort_order) FROM messages m2 WHERE m2.session_id = m.session_id
        )
    """)
    suspend fun lastMessageTailPerSession(): List<SessionTailRow>

    @Query("UPDATE sessions SET memory_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMemoryEnabled(id: String, enabled: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET thinking_override = :value, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateThinkingOverride(id: String, value: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET pinned_at = :pinnedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePinnedAt(id: String, pinnedAt: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET source = :source WHERE id = :id")
    suspend fun updateSource(id: String, source: String?)

    @Query("UPDATE messages SET stream_interrupt_count = stream_interrupt_count + 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun incrementStreamInterruptCount(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET parts_json = :partsJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMessageParts(id: String, partsJson: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET error_info = :errorInfo WHERE id = :messageId")
    suspend fun updateMessageErrorInfo(messageId: String, errorInfo: String?)

    @Query("""
        UPDATE messages SET error_info = :errorInfo
        WHERE id = (
            SELECT id FROM messages
            WHERE session_id = :sessionId AND role = 'assistant'
            ORDER BY sort_order DESC LIMIT 1
        )
    """)
    suspend fun updateLastAssistantError(sessionId: String, errorInfo: String?)

    @Query("""
        SELECT id, role, parts_json FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order DESC LIMIT 1 OFFSET :offset
    """)
    suspend fun messageHeadNewestFirst(sessionId: String, offset: Int): MessageHeadRow?

    @Query("SELECT * FROM sessions ORDER BY CASE WHEN pinned_at IS NOT NULL THEN 0 ELSE 1 END, pinned_at DESC, updated_at DESC")
    fun observeSessionsSorted(): Flow<List<ChatSessionEntity>>


    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCompactMarker(marker: CompactMarkerEntity)

    @androidx.room.Update
    suspend fun updateCompactMarker(marker: CompactMarkerEntity)

    @Query("SELECT * FROM compact_markers WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    suspend fun latestCompactMarker(sessionId: String): CompactMarkerEntity?

    @Query("SELECT * FROM compact_markers WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun listCompactMarkers(sessionId: String): List<CompactMarkerEntity>

    @Query("DELETE FROM compact_markers WHERE session_id = :sessionId")
    suspend fun deleteCompactMarkers(sessionId: String)

    @Query("DELETE FROM compact_markers WHERE id = :id")
    suspend fun deleteCompactMarker(id: String): Int


    @RawQuery
    suspend fun runSessionsMetaQuery(query: SupportSQLiteQuery): List<SessionMetaRow>

    @RawQuery
    suspend fun runMessageSearchQuery(query: SupportSQLiteQuery): List<MessageSearchRow>

    @Query("""
        SELECT * FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessagesPage(sessionId: String, offset: Int, limit: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE session_id = :sessionId
          AND (:startMs IS NULL OR created_at >= :startMs)
          AND (:endMs IS NULL OR created_at <= :endMs)
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessagesPageInRange(
        sessionId: String,
        offset: Int,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun messageCountForSession(sessionId: String): Int

    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE session_id = :sessionId
          AND (:startMs IS NULL OR created_at >= :startMs)
          AND (:endMs IS NULL OR created_at <= :endMs)
    """)
    suspend fun messageCountForSessionInRange(
        sessionId: String,
        startMs: Long?,
        endMs: Long?,
    ): Int
}

data class SessionSearchRow(
    @androidx.room.Embedded val session: ChatSessionEntity,
    @ColumnInfo(name = "hit_parts_json") val hitPartsJson: String?,
)

data class PartsJsonRow(
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

data class FolderMemberTitle(
    val folderId: String,
    val title: String,
    val updatedAt: Long,
)
