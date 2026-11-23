package com.openminis.app.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreInsertBenchmark {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context

    private val n = 2_000

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("bench.db")
        db = Room.databaseBuilder(ctx, AppDatabase::class.java, "bench.db").build()
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase("bench.db")
    }

    private fun session(i: Int) = ChatSessionEntity(
        id = "s$i", title = "t$i", modelId = "m",
        createdAt = 1L, updatedAt = 1L, category = null, lastMessage = null,
        modelBinding = null, source = null, memoryEnabled = 1, pinnedAt = null,
        editCount = 0, thinkingOverride = null, folderId = null,
        parentSessionId = null, parentToolUseId = null,
    )

    private fun message(i: Int, sid: String) = MessageEntity(
        id = "m$i", sessionId = sid, role = "user",
        partsJson = """[{"type":"text","text":"message body $i padded for realism"}]""",
        createdAt = i.toLong(), tokenUsage = null, sortOrder = i,
        reasoningContent = null, streamInterruptCount = 0, updatedAt = i.toLong(),
        errorInfo = null,
    )

    @Test
    fun batched_insert_is_dramatically_faster() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(session(0))

        val unbatchedMs = kotlin.system.measureTimeMillis {
            for (i in 0 until n) dao.insertMessage(message(i, "s0"))
        }

        dao.deleteMessages("s0")
        val batchedMs = kotlin.system.measureTimeMillis {
            db.withTransaction { for (i in 0 until n) dao.insertMessage(message(i, "s0")) }
        }

        val unbatchedRate = n * 1000.0 / unbatchedMs
        val batchedRate = n * 1000.0 / batchedMs
        val speedup = unbatchedMs.toDouble() / batchedMs

        android.util.Log.i("RestoreBench", "=== RESTORE INSERT BENCHMARK (n=$n) ===")
        android.util.Log.i("RestoreBench", "unbatched=${unbatchedMs}ms rate=${"%.0f".format(unbatchedRate)}/s per=${"%.2f".format(unbatchedMs.toDouble() / n)}ms")
        android.util.Log.i("RestoreBench", "batched=${batchedMs}ms rate=${"%.0f".format(batchedRate)}/s per=${"%.2f".format(batchedMs.toDouble() / n)}ms")
        android.util.Log.i("RestoreBench", "SPEEDUP=${"%.1f".format(speedup)}x")

        assertEquals("both arms must write every row", n, dao.messageCountForSession("s0"))
        assertTrue(
            "batching must be at least 2x faster (goal); measured ${"%.1f".format(speedup)}x",
            speedup >= 2.0,
        )
    }

    @Test
    fun set_lookup_beats_a_query_per_message() = runBlocking {
        val dao = db.chatDao()
        val ids = (0 until 200).map { "s$it" }
        db.withTransaction { ids.forEach { dao.insertSession(session(it.removePrefix("s").toInt())) } }
        val known = ids.toHashSet()

        val queryMs = kotlin.system.measureTimeMillis {
            db.withTransaction {
                for (i in 0 until n) {
                    val sid = ids[i % ids.size]
                    if (dao.getSession(sid) == null) continue
                }
            }
        }
        var hits = 0
        val setMs = kotlin.system.measureTimeMillis {
            for (i in 0 until n) {
                val sid = ids[i % ids.size]
                if (sid !in known) continue
                hits++
            }
        }
        val speedup = if (setMs == 0L) Double.POSITIVE_INFINITY else queryMs.toDouble() / setMs
        android.util.Log.i("RestoreBench", "parentCheck query=${queryMs}ms set=${setMs}ms " +
            "speedup=${if (speedup.isInfinite()) ">1000" else "%.0f".format(speedup)}x")
        assertEquals(n, hits)
        assertTrue("the set lookup must be faster", setMs <= queryMs)
    }
}
