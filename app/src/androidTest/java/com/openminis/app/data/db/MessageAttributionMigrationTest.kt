package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MessageAttributionMigrationTest {

    private lateinit var dbFile: File
    private lateinit var db: SQLiteDatabase
    private lateinit var helper: androidx.sqlite.db.SupportSQLiteOpenHelper
    private lateinit var supportDb: androidx.sqlite.db.SupportSQLiteDatabase

    private fun createV11Schema(d: SQLiteDatabase) {
        d.execSQL(
            """
            CREATE TABLE messages (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                parts_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                token_usage TEXT,
                sort_order INTEGER NOT NULL,
                reasoning_content TEXT,
                stream_interrupt_count INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER,
                error_info TEXT
            )
            """.trimIndent()
        )
        d.version = 11
    }

    private fun applyMigration(migration: androidx.room.migration.Migration) {
        migration.migrate(supportDb)
    }

    private fun columnNames(): Set<String> =
        db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            buildSet {
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) add(c.getString(idx))
            }
        }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = File(ctx.cacheDir, "migration-attribution-test.db")
        dbFile.delete()
        db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createV11Schema(db)
        db.close()

        val cfg = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
            .builder(ctx)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(d: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                override fun onUpgrade(
                    d: androidx.sqlite.db.SupportSQLiteDatabase, old: Int, new: Int,
                ) = Unit
            })
            .build()
        helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(cfg)
        supportDb = helper.writableDatabase
        db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    }

    @After
    fun tearDown() {
        db.close()
        helper.close()
        dbFile.delete()
    }

    @Test
    fun upgrade11To12_preservesExistingRows() {
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order) " +
                "VALUES ('m1','s1','assistant','[]',100,'{\"inputTokens\":7}',0)"
        )

        applyMigration(AppDatabase.MIGRATION_11_12)

        db.rawQuery("SELECT token_usage, model_id FROM messages WHERE id='m1'", null).use { c ->
            assertTrue("the pre-existing row must still be there", c.moveToFirst())
            assertEquals("{\"inputTokens\":7}", c.getString(0))
            assertTrue("pre-migration rows must have a NULL snapshot", c.isNull(1))
        }
    }

    @Test
    fun upgrade11To12_addsWritableSnapshotColumns() {
        applyMigration(AppDatabase.MIGRATION_11_12)

        assertTrue(
            columnNames().containsAll(
                listOf("model_id", "model_display_name", "provider_type", "provider_instance_id")
            )
        )
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order, " +
                "model_id, model_display_name, provider_type, provider_instance_id) VALUES " +
                "('m1','s1','assistant','[]',1,0,'grok-4.5','Grok 4.5','xAI','inst-1')"
        )
        db.rawQuery(
            "SELECT model_id, model_display_name, provider_type, provider_instance_id " +
                "FROM messages WHERE id='m1'", null
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("grok-4.5", c.getString(0))
            assertEquals("Grok 4.5", c.getString(1))
            assertEquals("xAI", c.getString(2))
            assertEquals("inst-1", c.getString(3))
        }
    }

    @Test
    fun downgrade12To11_runsWithoutLoss() {
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order) " +
                "VALUES ('m1','s1','assistant','[]',100,'{\"inputTokens\":7}',0)"
        )
        applyMigration(AppDatabase.MIGRATION_11_12)
        db.execSQL("UPDATE messages SET model_id='grok-4.5' WHERE id='m1'")

        applyMigration(AppDatabase.MIGRATION_12_11)

        db.rawQuery("SELECT COUNT(*) FROM messages", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("downgrade must not drop rows", 1, c.getInt(0))
        }
        db.rawQuery("SELECT model_id FROM messages WHERE id='m1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("grok-4.5", c.getString(0))
        }
    }

    @Test
    fun downgrade12To11_oldStyleInsertStillWorks() {
        applyMigration(AppDatabase.MIGRATION_11_12)
        applyMigration(AppDatabase.MIGRATION_12_11)

        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order) " +
                "VALUES ('m2','s2','user','[]',1,0)"
        )
        db.rawQuery("SELECT model_id FROM messages WHERE id='m2'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("unset snapshot columns must be NULL", c.isNull(0))
        }
        assertTrue(columnNames().contains("model_id"))
    }

    @Test
    fun downgrade12To11_selectStarStillReadsOldColumns() {
        applyMigration(AppDatabase.MIGRATION_11_12)
        applyMigration(AppDatabase.MIGRATION_12_11)
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order) " +
                "VALUES ('m3','s3','user','[]',5,0)"
        )
        db.rawQuery("SELECT * FROM messages WHERE id='m3'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("m3", c.getString(c.getColumnIndexOrThrow("id")))
            assertEquals(5, c.getLong(c.getColumnIndexOrThrow("created_at")))
            assertNull(c.getString(c.getColumnIndexOrThrow("model_id")))
        }
    }
}
