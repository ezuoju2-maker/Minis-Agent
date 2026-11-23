package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Review0923SubAgentQueueInvariantsTest {

    private val vm: String by lazy { read("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }

    private fun read(path: String): String =
        File(path).also { assertTrue("missing ${it.absolutePath}", it.exists()) }.readText()

    private fun body(name: String): String {
        val start = vm.indexOf("fun $name(")
        assertTrue("function $name not found", start >= 0)
        val next = Regex("""\n    (private |internal |public )?(suspend )?fun \w+\(""")
            .find(vm, start + 10)?.range?.first ?: vm.length
        return vm.substring(start, next)
    }


    @Test
    fun `every successful finish of a delegated run stamps the summary first`() {
        val files = listOf(
            "src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt",
            "src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt",
        )
        var checked = 0
        for (f in files) {
            val lines = read(f).lines()
            lines.forEachIndexed { i, line ->
                if (!line.contains("registry.finish(")) return@forEachIndexed
                val call = lines.subList(i, minOf(i + 3, lines.size)).joinToString(" ")
                if (call.contains("AgentJobState.FAILED") || call.contains("AgentJobState.CANCELLED")) return@forEachIndexed
                checked++
                val before = lines.subList(maxOf(0, i - 8), i).joinToString("\n")
                assertTrue(
                    "$f:${i + 1} finishes a run without setSummaryLine just before it",
                    before.contains("setSummaryLine("),
                )
            }
        }
        assertTrue("expected the wait, background and scheduled paths (found $checked)", checked >= 3)
    }


    @Test
    fun `a resumed sub agent run is finished when the child goes idle`() {
        val b = body("resumeInterruptedChild")
        assertTrue("sanity: this is the resume path", b.contains("resumeNotice()"))
        val waitsForIdle = b.contains("isStreaming.first { !it }") ||
            b.contains("startBackgroundHelper(") ||
            b.contains("setCompletionHook(")
        assertTrue(
            "resumeInterruptedChild must close its job when the child finishes; " +
                "today it only finishes on exception, so a resumed job stays RUNNING forever",
            waitsForIdle,
        )
    }


    @Test
    fun `the mid-task inject path cannot lose queued prompts to a Stop`() {
        for (name in listOf("injectQueuedPromptsAsNewTurn", "drainQueuedPrompts")) {
            val b = body(name)
            assertTrue("$name: the queue must not be emptied wholesale", !b.contains("_promptQueue.value = emptyList()"))
            val persist = b.indexOf("chatRepository.appendMessage(")
            val dequeue = b.indexOf("_promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }", persist)
            assertTrue("$name: sanity: persist present", persist >= 0)
            val restoresOnCancel = Regex("""catch \([^)]*CancellationException[^)]*\)[^}]*_promptQueue""")
                .containsMatchIn(b)
            assertTrue(
                "$name: queue is dequeued before the suspending persist and never restored on cancellation",
                dequeue > persist || restoresOnCancel,
            )
        }
    }

    @Test
    fun `the inject emit filters the live message list, not a snapshot`() {
        val b = body("injectQueuedPromptsAsNewTurn")
        assertTrue(b.contains("_messages.value = _messages.value.filterNot"))
        assertTrue("no stale snapshot variable", !b.contains("msgsAfterUnqueue ="))
    }
}
