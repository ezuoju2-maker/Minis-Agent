package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
        FolderEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN last_message TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN model_binding TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_content TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN first_kept_message_id TEXT")
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN last_compacted_message_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN thinking_override TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pwa_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO webapp_shortcuts (
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    )
                    SELECT
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    FROM pwa_shortcuts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN error_info TEXT")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT,
                        color TEXT,
                        origin TEXT NOT NULL DEFAULT 'manual',
                        sort_index INTEGER NOT NULL DEFAULT 0,
                        pinned_at INTEGER,
                        description TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE sessions ADD COLUMN folder_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions(folder_id)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN memory_enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned_at INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN edit_count INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE messages ADD COLUMN stream_interrupt_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS compact_markers (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        first_kept_sort_order INTEGER NOT NULL,
                        compacted_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        ui_boundary_sort_order INTEGER,
                        boundary_message_id TEXT,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN model_display_name TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN provider_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN provider_instance_id TEXT")
            }
        }

        val MIGRATION_12_11 = object : Migration(12, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN parent_session_id TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN parent_tool_use_id TEXT")
            }
        }

        val MIGRATION_13_12 = object : Migration(13, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sessions_updated_at " +
                        "ON sessions(updated_at)",
                )
            }
        }

        val MIGRATION_14_13 = object : Migration(14, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_sessions_updated_at")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minis.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_12, MIGRATION_12_11,
                        MIGRATION_13_14, MIGRATION_14_13,
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
