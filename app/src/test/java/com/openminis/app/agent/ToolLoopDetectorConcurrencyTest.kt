package com.openminis.app.agent

import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopDetectorConcurrencyTest {

    @Test
    fun `concurrent check and record never expose a null record`() = runBlocking {
        repeat(12) {
            val d = ToolLoopDetector()
            coroutineScope {
                (0 until 24).map { i ->
                    async(Dispatchers.Default) {
                        repeat(40) { n ->
                            val params = mapOf("q" to "v$i-$n")
                            d.check("shell_execute", params)
                            d.record("shell_execute", params, result = "ok$n", toolCallId = "c$i-$n")
                            d.historySnapshot().forEach { r ->
                                assertTrue("a record must never be null-ish", r.toolName.isNotEmpty())
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
