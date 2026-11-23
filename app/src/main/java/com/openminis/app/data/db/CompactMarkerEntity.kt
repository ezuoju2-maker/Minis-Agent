package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "compact_markers",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["first_kept_message_id"]),
    ]
)
data class CompactMarkerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val summary: String,
    @ColumnInfo(name = "first_kept_sort_order") val firstKeptSortOrder: Int,
    @ColumnInfo(name = "compacted_count") val compactedCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "ui_boundary_sort_order") val uiBoundarySortOrder: Int? = null,
    @ColumnInfo(name = "boundary_message_id") val boundaryMessageId: String? = null,
    @ColumnInfo(name = "first_kept_message_id") val firstKeptMessageId: String? = null,
    @ColumnInfo(name = "last_compacted_message_id") val lastCompactedMessageId: String? = null,
    val version: Int = 1,
)
