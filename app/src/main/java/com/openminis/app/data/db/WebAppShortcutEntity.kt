package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "webapp_shortcuts")
data class WebAppShortcutEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "html_path") val htmlPath: String,
    @ColumnInfo(name = "path_scope") val pathScope: String,
    @ColumnInfo(name = "scope_context") val scopeContext: String?,
    val title: String,
    @ColumnInfo(name = "icon_ref") val iconRef: String,
    @ColumnInfo(name = "icon_cache_path") val iconCachePath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String?,
)
