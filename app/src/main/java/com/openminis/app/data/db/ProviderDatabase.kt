package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderInstanceEntity::class,
        ProviderModelEntryEntity::class,
        ProviderModelGroupEntity::class,
        ProviderAgentLoopIdEntity::class,
        ProviderConfigMetaEntity::class,
        ProviderThinkingRuleEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class ProviderDatabase : RoomDatabase() {
    abstract fun providerConfigDao(): ProviderConfigDao

    companion object {
        @Volatile
        private var INSTANCE: ProviderDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN azure_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_mode TEXT")
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_resolved TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_thinking_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider_instance_id TEXT NOT NULL,
                        label TEXT NOT NULL,
                        scope_kind TEXT NOT NULL,
                        scope_pattern TEXT,
                        wire_format_json TEXT,
                        reasoning_echo_json TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_thinking_rules_provider_instance_id " +
                        "ON provider_thinking_rules(provider_instance_id)",
                )
            }
        }


        fun getInstance(context: Context): ProviderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProviderDatabase::class.java,
                    "provider.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
