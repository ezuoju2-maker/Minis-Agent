package com.openminis.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationChainTest {

    private val migrations = AppDatabase.allMigrations

    private val version: Int =
        AppDatabase::class.java.getAnnotation(androidx.room.Database::class.java)?.version ?: 0

    @Test
    fun forwardsCoverEveryVersionStep() {
        val steps = migrations.filter { it.endVersion > it.startVersion }
            .map { it.startVersion to it.endVersion }
        val missing = (1 until version).filter { v ->
            steps.none { it.first == v && it.second == v + 1 }
        }
        assertEquals("missing forward migrations: $missing", emptyList<Int>(), missing)
    }

    @Test
    fun noForwardMigrationSkipsAVersion() {
        val skipped = migrations.filter { it.endVersion > it.startVersion }
            .filter { it.endVersion - it.startVersion != 1 }
            .map { "${it.startVersion}->${it.endVersion}" }
        assertEquals("multi-version jumps hide untested ground: $skipped", emptyList<String>(), skipped)
    }

    @Test
    fun downgradesMirrorTheLastTwoSteps() {
        val forward = migrations.filter { it.endVersion > it.startVersion }
            .associate { (it.startVersion to it.endVersion) to it }
        val missing = forward.keys.filter { (from, to) ->
            from >= 12 && migrations.none { it.startVersion == to && it.endVersion == from }
        }.map { (from, to) -> "$from->$to" }
        assertEquals("missing downgrade paths for recent versions: $missing", emptyList<String>(), missing)
    }

    @Test
    fun everyMigrationIsRegisteredOnce() {
        val duplicates = migrations.groupBy { it.startVersion to it.endVersion }
            .filter { it.value.size > 1 }
            .map { "${it.key.first}->${it.key.second}" }
        assertEquals("duplicate migrations: $duplicates", emptyList<String>(), duplicates)
        assertTrue("expected a real version, got $version", version >= 10)
        assertTrue("expected more than 10 migrations, found ${migrations.size}", migrations.size > 10)
    }
}
