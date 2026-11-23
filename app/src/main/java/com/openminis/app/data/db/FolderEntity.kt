package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    val origin: String = ORIGIN_MANUAL,
    @ColumnInfo(name = "sort_index") val sortIndex: Int = 0,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    val description: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val isPinned: Boolean get() = pinnedAt != null

    companion object {
        const val ORIGIN_MANUAL = "manual"
        const val ORIGIN_AI = "ai"

        const val DESC_MAX_CHARS = 100
    }
}
