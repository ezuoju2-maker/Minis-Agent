package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMultiTaskIndicatorTest {


    private fun effectiveToolTitle(
        liveTitle: String?,
        lastTitle: String?,
        isRunning: Boolean,
        isThinking: Boolean,
        hasActiveStream: Boolean,
    ): String? {
        val stillWorking = isRunning || isThinking || hasActiveStream
        return liveTitle ?: lastTitle?.takeIf { stillWorking }
    }

    private fun oldEffectiveToolTitle(liveTitle: String?, lastTitle: String?): String? =
        liveTitle ?: lastTitle

    @Test
    fun `the reported case - a finished tool stops being shown as running`() {
        val title = effectiveToolTitle(
            liveTitle = null,
            lastTitle = "验证界面复刻 APK",
            isRunning = false,
            isThinking = false,
            hasActiveStream = false,
        )
        assertNull(title)

        assertEquals(
            "old rule must fail here",
            "验证界面复刻 APK",
            oldEffectiveToolTitle(null, "验证界面复刻 APK"),
        )
    }

    @Test
    fun `the snapshot survives a streaming stretch between two tool calls`() {
        assertEquals(
            "验证界面复刻 APK",
            effectiveToolTitle(null, "验证界面复刻 APK", false, false, hasActiveStream = true),
        )
        assertEquals(
            "验证界面复刻 APK",
            effectiveToolTitle(null, "验证界面复刻 APK", false, isThinking = true, hasActiveStream = false),
        )
    }

    @Test
    fun `a live tool always wins over the snapshot`() {
        assertEquals(
            "搜索天气数据",
            effectiveToolTitle("搜索天气数据", "验证界面复刻 APK", true, false, true),
        )
    }


    private data class Task(val id: String, val title: String, val finished: Boolean = false)

    private fun slot(tasks: List<Task>, focusedId: String?): Triple<Task, Int, Int>? {
        if (tasks.isEmpty()) return null
        val i = tasks.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 } ?: tasks.lastIndex
        return Triple(tasks[i], i + 1, tasks.size)
    }

    @Test
    fun `a single task reports one of one`() {
        val s = slot(listOf(Task("a", "Find Android Dev Minis Skill")), null)!!
        assertEquals(1, s.second)
        assertEquals(1, s.third)
    }

    @Test
    fun `with no explicit focus the capsule follows the newest task`() {
        val tasks = listOf(Task("a", "A"), Task("b", "B"))
        val s = slot(tasks, focusedId = null)!!
        assertEquals("B", s.first.title)
        assertEquals(2, s.second)
    }

    @Test
    fun `a finished task keeps its index and stays in the total`() {
        val tasks = listOf(Task("a", "A", finished = true), Task("b", "B"))
        val s = slot(tasks, focusedId = "a")!!
        assertEquals(1, s.second)
        assertEquals(2, s.third)
        assertTrue(s.first.finished)
    }

    @Test
    fun `finishing a task never renumbers the one beside it`() {
        val before = listOf(Task("a", "A"), Task("b", "B"))
        assertEquals(2, slot(before, "b")!!.second)

        val afterAFinished = listOf(Task("a", "A", finished = true), Task("b", "B"))
        assertEquals(2, slot(afterAFinished, "b")!!.second)
    }

    @Test
    fun `focus survives a slot leaving because it is held by id not index`() {
        val tasks = listOf(Task("a", "A"), Task("b", "B"), Task("c", "C"))
        assertEquals("C", slot(tasks, "c")!!.first.title)
        val dropped = tasks.filterNot { it.id == "a" }
        val s = slot(dropped, "c")!!
        assertEquals("C", s.first.title)
        assertEquals(2, s.second)
        assertEquals(2, s.third)
    }

    @Test
    fun `an empty task list yields no slot`() {
        assertNull(slot(emptyList(), null))
    }


    private fun cycle(tasks: List<Task>, focusedId: String?): String? {
        if (tasks.size < 2) return focusedId
        val cur = focusedId ?: tasks.first().id
        val i = tasks.indexOfFirst { it.id == cur }
        return tasks[(i + 1) % tasks.size].id
    }

    @Test
    fun `cycling advances and wraps`() {
        val tasks = listOf(Task("a", "A"), Task("b", "B"), Task("c", "C"))
        assertEquals("b", cycle(tasks, "a"))
        assertEquals("c", cycle(tasks, "b"))
        assertEquals("a", cycle(tasks, "c"))
    }

    @Test
    fun `cycling a single task is a no-op`() {
        val tasks = listOf(Task("a", "A"))
        assertEquals("a", cycle(tasks, "a"))
    }


    @Test
    fun `the index is a plain number in every locale`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "no circled-digit arithmetic may come back",
            !src.contains("'\u2460' +") && !src.contains("in 1..20"),
        )
        assertTrue(
            "the index is passed to the string as an Int",
            src.contains("R.string.overlay_task_index, taskIndex, taskTotal"),
        )
    }

    @Test
    fun `the chip is an outline with no fill`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "radius must track half the chip height, as the capsule's does",
            src.contains("cornerRadius = TASK_PILL_HEIGHT_DP / 2f * density"),
        )
        val chip = src.substringAfter("TASK_PILL_HEIGHT_DP / 2f * density")
            .substringBefore("}")
        assertTrue("the chip must not be filled", chip.contains("setColor(Color.TRANSPARENT)"))
        assertTrue("the chip keeps its hairline border", chip.contains("setStroke(dpToPx(1)"))
    }

    @Test
    fun `non-Latin locales render the index without the English word`() {
        val res = generateSequence(ProductionSources.mainRoot()!!) { it.parentFile }
            .first { java.io.File(it, "res/values/strings.xml").isFile }
            .resolve("res")
        val base = java.io.File(res, "values/strings.xml").readText()
        assertTrue(
            "the source locale keeps the word",
            base.contains("""<string name="overlay_task_index">%1${'$'}d of %2${'$'}d</string>"""),
        )
        for (loc in listOf("zh", "zh-rTW", "ja", "ko", "th")) {
            val f = java.io.File(res, "values-$loc/strings.xml")
            assertTrue("missing locale file: $loc", f.isFile)
            assertTrue(
                "$loc must render the index as n/m, not \"n of m\"",
                f.readText().contains(
                    """<string name="overlay_task_index">%1${'$'}d/%2${'$'}d</string>""",
                ),
            )
        }
    }

    @Test
    fun `every locale that declares the index uses integer placeholders`() {
        val res = generateSequence(ProductionSources.mainRoot()!!) { it.parentFile }
            .first { java.io.File(it, "res/values/strings.xml").isFile }
            .resolve("res")
        val offenders = res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .mapNotNull { dir ->
                val f = java.io.File(dir, "strings.xml")
                if (!f.isFile) return@mapNotNull null
                val line = f.readText().lines().firstOrNull { "overlay_task_index" in it && "<string" in it }
                    ?: return@mapNotNull null
                if ("%1${'$'}s" in line) dir.name else null
            }
        assertEquals("locales still using a string placeholder", emptyList<String>(), offenders)
    }


    private fun overlaySessionTitle(raw: String): String? =
        raw.takeIf { it.isNotBlank() && it != "New Chat" }

    private fun rowOne(sessionTitle: String?, completionWord: String?, soulName: String): String =
        sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: soulName

    @Test
    fun `an untitled session shows the assistant name, not the placeholder`() {
        assertNull(overlaySessionTitle("New Chat"))
        assertEquals("Pikachu", rowOne(overlaySessionTitle("New Chat"), null, "Pikachu"))
    }

    @Test
    fun `a blank title also falls back to the assistant name`() {
        assertNull(overlaySessionTitle(""))
        assertNull(overlaySessionTitle("   "))
        assertEquals("Pikachu", rowOne(overlaySessionTitle("  "), null, "Pikachu"))
    }

    @Test
    fun `a real title wins over both the completion word and the Soul name`() {
        val t = overlaySessionTitle("Find Android Dev Minis Skill")
        assertEquals("Find Android Dev Minis Skill", t)
        assertEquals("Find Android Dev Minis Skill", rowOne(t, "执行完成", "Pikachu"))
    }

    @Test
    fun `the placeholder is a named constant, matched where it is defined`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(
            "the sentinel must be a named constant",
            vm.contains("const val UNTITLED_SESSION_TITLE = \"New Chat\""),
        )
        assertTrue(
            "the helper must exist",
            vm.contains("private fun overlaySessionTitle(): String?"),
        )
        val routed = Regex("sessionTitle = overlaySessionTitle\\(\\)").findAll(vm).count()
        val raw = Regex("sessionTitle = _sessionTitle\\.value").findAll(vm).count()
        assertEquals("no overlay call site may pass the raw title", 0, raw)
        assertTrue("expected the overlay call sites to route through the helper", routed >= 5)
        val overlay = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "the capsule must not know the sentinel",
            !overlay.contains("New Chat"),
        )
    }


    @Test
    fun `every path that hides the capsule also drops its task slots`() {
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue(
            "the hide+clear pair must live in one helper",
            src.contains("private fun hideOverlay()"),
        )
        val direct = Regex("controller\\.hide\\(\\)").findAll(src).count()
        assertEquals("every hide must route through hideOverlay()", 0, direct)
        assertTrue(
            "hideOverlay must drop the finished slots",
            src.substringAfter("private fun hideOverlay()")
                .substringBefore("\n    }")
                .contains("dropFinishedOverlayTasks()"),
        )
    }

    @Test
    fun `a fresh run after a cleared capsule starts from one`() {
        var tasks = listOf(Task("a", "A", finished = true), Task("b", "B", finished = true))
        tasks = emptyList()
        tasks = tasks + Task("c", "C")
        val s = slot(tasks, null)!!
        assertEquals(1, s.second)
        assertEquals(1, s.third)
    }


    @Test
    fun `the indicator is hidden below two tasks`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "index must be gated on taskTotal >= 2",
            src.contains("taskTotal >= 2"),
        )
    }

    @Test
    fun `row one shows the session title with the Soul name only as fallback`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(
            "session title must take precedence on the identity row",
            src.contains("sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: identityName"),
        )
    }

    @Test
    fun `the clock sits on row two under the index`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue("clock must be added to the status row", src.contains("statusRow.addView(elapsed)"))
        assertTrue("index must be added to the label row", src.contains("labelRow.addView(taskIndex)"))
    }

    @Test
    fun `the stale-tool gate is still in the service`() {
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue(
            "fallback must be gated on the turn still working",
            src.contains("state.lastToolName?.takeIf { stillWorkingThisTurn }"),
        )
    }

    @Test
    fun `title and index are resolved from one snapshot`() {
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        assertEquals(
            "focusedTaskSlot must be read exactly once per render",
            1,
            Regex("SessionActivityTracker\\.focusedTaskSlot\\(\\)").findAll(src).count(),
        )
    }
}
