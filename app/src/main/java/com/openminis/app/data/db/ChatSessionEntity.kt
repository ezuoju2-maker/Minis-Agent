package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    indices = [
        androidx.room.Index(value = ["folder_id"], name = "index_sessions_folder_id"),
        androidx.room.Index(value = ["updated_at"], name = "index_sessions_updated_at"),
    ],
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val category: String? = null,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "model_binding") val modelBinding: String? = null,
    @ColumnInfo(name = "source") val source: String? = null,
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    @ColumnInfo(name = "edit_count") val editCount: Int = 0,
    @ColumnInfo(name = "thinking_override") val thinkingOverride: String? = null,
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,
    @ColumnInfo(name = "parent_tool_use_id") val parentToolUseId: String? = null,
) {
    val isChild: Boolean get() = parentSessionId != null
}
