package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobThen
import com.openminis.app.agent.jobs.AgentJobTrigger
import com.openminis.app.data.model.LLMError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class StreamWatchdogAndCancelTest {

    private companion object {
        const val STREAM_TTFB_TIMEOUT_MS = 120_000L
        const val STREAM_UPLOAD_CAP_MS = 120_000L
        const val LEGACY_SINGLE_WINDOW_MS = 30_000L
        const val MS = 1_000_000L
    }

    private fun timedOutPhase(callStartNanos: Long, uploadDoneAtNanos: Long, nowNanos: Long): String? {
        if (uploadDoneAtNanos == 0L) {
            val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
            if (elapsedMs >= STREAM_UPLOAD_CAP_MS) return "upload"
        } else {
            val sinceUploadMs = (nowNanos - uploadDoneAtNanos) / 1_000_000L
            if (sinceUploadMs >= STREAM_TTFB_TIMEOUT_MS) return "ttfb"
        }
        return null
    }

    private fun runWatchdog(uploadAtMs: Long?, headersAtMs: Long?, horizonMs: Long = 400_000L): String? {
        val start = 7_000_000L
        var t = 0L
        while (t <= horizonMs) {
            if (headersAtMs != null && t >= headersAtMs) return null
            val uploadDone = if (uploadAtMs != null && t >= uploadAtMs) start + uploadAtMs * MS else 0L
            timedOutPhase(start, uploadDone, start + t * MS)?.let { return it }
            t += 250
        }
        return null
    }


    @Test
    fun `slow upload plus normal TTFB is not cancelled`() {
        assertNull(runWatchdog(uploadAtMs = 25_000, headersAtMs = 35_000))
        assertTrue("legacy rule would have mis-cancelled this healthy request", 35_000 > LEGACY_SINGLE_WINDOW_MS)
    }

    @Test
    fun `the ttfb clock starts at upload completion`() {
        assertNull(runWatchdog(uploadAtMs = 100_000, headersAtMs = 215_000))
        assertEquals("ttfb", runWatchdog(uploadAtMs = 100_000, headersAtMs = 230_000))
    }

    @Test
    fun `a stalled upload is bounded by the upload cap`() {
        assertEquals("upload", runWatchdog(uploadAtMs = null, headersAtMs = null))
        assertNull("headers before the cap end the watch", runWatchdog(uploadAtMs = null, headersAtMs = 119_000))
    }

    @Test
    fun `headers arriving stop the watchdog in either phase`() {
        assertNull(runWatchdog(uploadAtMs = 1_000, headersAtMs = 2_000))
        assertNull(runWatchdog(uploadAtMs = 119_000, headersAtMs = 119_500))
    }

    @Test
    fun `the watchdog error is transient without an http status`() {
        val err = LLMError.TransientError("no response from server (${STREAM_TTFB_TIMEOUT_MS / 1000}s TTFB) — check network/proxy")
        assertTrue(err.isRetryable)
        assertNull(err.httpServerErrorStatus)
        assertFalse(err.isHttpServerError)
        assertFalse(err.isFallbackable)
    }

    private val providerSrc by lazy {
        File("src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt").readText()
    }

    private fun sourceLongConst(name: String): Long {
        val m = Regex("""private const val $name = ([0-9_]+)L""").find(providerSrc)
            ?: throw AssertionError("$name not declared as a Long const in OpenAIProvider.kt")
        return m.groupValues[1].replace("_", "").toLong()
    }

    @Test
    fun `production watchdog still measures from requestBodyEnd and evicts one connection`() {
        assertEquals(STREAM_TTFB_TIMEOUT_MS, sourceLongConst("STREAM_TTFB_TIMEOUT_MS"))
        assertEquals(STREAM_UPLOAD_CAP_MS, sourceLongConst("STREAM_UPLOAD_CAP_MS"))
        assertTrue(providerSrc.contains("val uploadDoneAt = watchState.uploadDoneAtNanos.get()"))
        assertTrue(providerSrc.contains("""if (elapsedMs >= STREAM_UPLOAD_CAP_MS) { timedOutPhase = "upload"; break }"""))
        assertTrue(providerSrc.contains("""if (sinceUploadMs >= ttfbBudgetMs) { timedOutPhase = "ttfb"; break }"""))
        assertTrue(providerSrc.contains(
            "val ttfbBudgetMs = if (usesChatCompletionsAPI) responseIdleTimeoutSec * 1000L else STREAM_TTFB_TIMEOUT_MS"))
        assertEquals(STREAM_TTFB_TIMEOUT_MS, com.openminis.app.provider.ResponseIdleTimeoutSetting.DEFAULT_SECONDS * 1000L)
        assertTrue("upload completion is signalled from requestBodyEnd", providerSrc.contains("override fun requestBodyEnd"))
        assertTrue(providerSrc.contains("?.uploadDoneAtNanos?.set(System.nanoTime())"))
        val timeoutBlock = providerSrc.substringAfter("if (timedOutPhase != null && !headersArrived.get())").substringBefore("val response = try {")
        assertTrue(timeoutBlock.contains("call.cancel()"))
        assertTrue(timeoutBlock.contains("watchState.connection.get()?.socket()?.close()"))
        assertFalse("never evict the whole pool for one dead call", timeoutBlock.contains("evictAll("))
    }


    private fun <T> window(full: List<T>, cap: Int, threshold: Int = 50): List<T> =
        if (full.size <= threshold || full.size <= cap) full
        else full.subList(full.size - cap, full.size).toList()

    @Test
    fun `stop during streaming writes never throws and the final state is consistent`() {
        val state = AtomicReference<List<String>>(emptyList())
        val failure = AtomicReference<Throwable?>(null)
        val rounds = 3_000
        val producer = Thread {
            try {
                repeat(rounds) { i ->
                    state.set(state.get() + "m$i")
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        val consumer = Thread {
            try {
                var prev: List<String> = emptyList()
                var diffs = 0
                while (producer.isAlive || diffs < rounds) {
                    val cur = window(state.get(), cap = 40)
                    if (cur != prev) { prev = cur; diffs++ }
                    if (!producer.isAlive) break
                }
                val kept = state.get().let { it.subList(0, minOf(10, it.size)).toList() }
                val again = window(state.get(), cap = 40)
                check(kept != again || state.get().size <= 10)
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        producer.start(); consumer.start()
        producer.join(); consumer.join()
        assertNull("no thread may throw: ${failure.get()}", failure.get())
        assertEquals(rounds, state.get().size)
        assertEquals(40, window(state.get(), cap = 40).size)
        assertEquals("m${rounds - 1}", window(state.get(), cap = 40).last())
    }

    @Test
    fun `short sessions return the same list instance`() {
        val raw = List(10) { "m$it" }
        assertTrue(window(raw, cap = 40) === raw)
    }

    private val vmSrc by lazy { File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText() }

    @Test
    fun `production ui window and truncation still copy instead of sharing a live view`() {
        assertTrue(vmSrc.contains("else full.subList(full.size - cap, full.size).toList()"))
        assertTrue(vmSrc.contains("val kept = messages.subList(0, cutFrom).toList()"))
    }

    private val parent = "P-cancel"

    private fun cancelStreamPort(isStreaming: AtomicReference<Boolean>) {
        AgentJobRegistry.muteDelegationResults(parent)
        AgentJobRegistry.dropQueuedDelegations(parent, "user-stopped")
        AgentJobRegistry.cancelAll(parent, "user-stopped")
        isStreaming.set(false)
    }

    @Before
    fun resetRegistry() {
        AgentJobRegistry.resetForTest()
        AgentJobRegistry.dropQueuedDelegations(parent, "reset")
    }

    @After
    fun clearRegistry() {
        AgentJobRegistry.followUpDispatcher = null
        AgentJobRegistry.unregisterQueuedStarter(parent)
        AgentJobRegistry.dropQueuedDelegations(parent, "teardown")
        AgentJobRegistry.resetForTest()
    }

    private fun child(t: String) = AgentJobRegistry.register(
        title = t, origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tu-$t"), prompt = "x",
        then = AgentJobThen.FollowUpParent(null),
    )

    @Test
    fun `stop mutes the parent, drops the queue and cancels the fan-out before the flag flips`() {
        val dispatched = mutableListOf<String>()
        AgentJobRegistry.followUpDispatcher = { _, _, id -> dispatched += id }
        var started = 0
        AgentJobRegistry.registerQueuedStarter(parent) { started++; true }
        val a = child("a"); val b = child("b")
        AgentJobRegistry.markRunning(a.id, "S-a") {}
        AgentJobRegistry.markRunning(b.id, "S-b") {}
        assertTrue(AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation(parent, "{}", "tu-q")))
        val streaming = AtomicReference(true)

        cancelStreamPort(streaming)

        assertFalse(streaming.get())
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(a.id)?.state)
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(b.id)?.state)
        assertEquals("the queue was emptied before any slot was freed", 0, AgentJobRegistry.queuedCount(parent))
        assertEquals(0, started)
        assertEquals("cancelled children do not wake the stopped parent", emptyList<String>(), dispatched)
        val late = child("late")
        AgentJobRegistry.markRunning(late.id, "S-late") {}
        AgentJobRegistry.finish(late.id, AgentJobState.DONE, "r")
        assertEquals(emptyList<String>(), dispatched)
    }

    @Test
    fun `production cancelStream keeps the mute → drop → cancel order`() {
        val body = vmSrc.substringAfter("fun cancelStream(keepBackground: Boolean = false) {").substringBefore("streamJob?.cancel()")
        val mute = body.indexOf("AgentJobRegistry.muteDelegationResults(activeSessionId)")
        val drop = body.indexOf("AgentJobRegistry.dropQueuedDelegations(activeSessionId, \"user-stopped\")")
        val cancel = body.indexOf("AgentJobRegistry.cancelAll(activeSessionId, \"user-stopped\")")
        assertTrue("mute must come first", mute in 0 until drop)
        assertTrue("drop must come before cancel", drop in 0 until cancel)
        assertTrue(
            "the streaming flag flips only after the fan-out is stopped",
            vmSrc.substringAfter("fun cancelStream(keepBackground: Boolean = false) {").indexOf("_isStreaming.value = false") > cancel,
        )
    }
}
