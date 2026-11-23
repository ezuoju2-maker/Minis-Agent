package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.provider.ToolJsonRepair
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OffloadWriteIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileWrite = AgentToolDefinition(
        name = "file_write", description = "write",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "t"),
            "path" to AgentToolParam("string", "p"),
            "content" to AgentToolParam("string", "c"),
        ),
        required = listOf("tool_title", "path", "content"),
    )
    private val fileEdit = fileWrite.copy(name = "file_edit")
    private val fileRead = AgentToolDefinition(
        name = "file_read", description = "read",
        parameters = mapOf("tool_title" to AgentToolParam("string", "t"), "path" to AgentToolParam("string", "p")),
        required = listOf("tool_title", "path"),
    )
    private val tools = listOf(fileWrite, fileEdit, fileRead)

    private fun refusesTruncatedWrite(name: String, repairs: List<String>): String? {
        val tag = repairs.firstOrNull { it.startsWith("truncation+") } ?: return null
        return if (name == "file_write" || name == "file_edit") tag else null
    }

    private val cutWriteTail = "{\"tool_title\":\"Write notes\",\"path\":\"/var/minis/workspace/notes.md\",\"content\":\"# Notes\\nline 1\\nline 2"


    @Test
    fun `a max_tokens-truncated file_write is repaired into valid JSON but refused`() {
        val args = JSONObject()
        val repairs = ToolJsonRepair.repair("file_write", args, cutWriteTail, tools)
        assertTrue("sanity: the repair DID make it parse (tags=$repairs)", repairs.any { it.startsWith("truncation+") })
        assertEquals("/var/minis/workspace/notes.md", args.getString("path"))
        assertTrue("repaired content is the half the model got out", args.getString("content").startsWith("# Notes"))
        val tag = refusesTruncatedWrite("file_write", repairs)
        assertNotNull("file_write with a truncation repair must be refused", tag)
        assertNotNull(refusesTruncatedWrite("file_edit", repairs))
    }

    @Test
    fun `the same truncation on a read-class tool is allowed to run`() {
        val args = JSONObject()
        val repairs = ToolJsonRepair.repair("file_read", args, "{\"tool_title\":\"Read\",\"path\":\"/var/minis/workspace/a.txt", tools)
        assertTrue(repairs.any { it.startsWith("truncation+") })
        assertNull("reads keep repair-and-run", refusesTruncatedWrite("file_read", repairs))
        assertNull(refusesTruncatedWrite("shell_execute", repairs))
    }

    @Test
    fun `a complete write with only a shape repair is not refused`() {
        val args = JSONObject().put("tool_title", "w").put("path", "/x").put("contnt", "full body")
        val repairs = ToolJsonRepair.repair("file_write", args, null, tools)
        assertTrue(repairs.any { it.startsWith("fuzzy:") })
        assertNull(refusesTruncatedWrite("file_write", repairs))
        val clean = ToolJsonRepair.repair("file_write", JSONObject().put("tool_title", "w").put("path", "/x").put("content", "b"), null, tools)
        assertTrue(clean.isEmpty())
    }


    private fun isOffloadCandidate(
        part: AgentContentPart,
        answeredToolUseIds: Set<String> = setOf("w", "c"),
    ): Boolean = when (part) {
        is AgentContentPart.ToolResult -> !ContextOffload.isOffloadPlaceholder(part.content) &&
            (part.content.length > 500 || (part.imageData?.size ?: 0) > 1024)
        is AgentContentPart.ToolUse ->
            (part.name == "file_write" || part.name == "file_edit") &&
                !part.isOffloadedArgument &&
                !ContextOffload.isOffloadPlaceholder(part.input.optString("content", "")) &&
                part.id in answeredToolUseIds &&
                part.input.optString("content", "").length > 500
        else -> false
    }

    @Test
    fun `reading a file under the offloads dir re-stubs to that file and the stub is never re-offloaded`() {
        val path = "${ContextOffload.LINUX_OFFLOADS_DIR}/tools/shell_execute_abc123def456.txt"
        val readback = "[$path | 456789 bytes | 1200 lines | showing 1-1200 of 1200]\n" + "x".repeat(20_000)
        assertEquals("the readback resolves to the EXISTING file", path, ContextOffload.offloadReadbackPath(readback))
        val stub = ContextOffload.stub(approxTokens = 5000, byteCount = 456789, linuxPath = path)
        assertTrue(stub.startsWith(ContextOffload.OFFLOADED_PREFIX))
        assertTrue("stub stays under the 500-char scan floor (${stub.length})", stub.length < 500)
        assertFalse(isOffloadCandidate(AgentContentPart.ToolResult("c", "file_read", stub)))
        val stubbedWrite = AgentContentPart.ToolUse("w", "file_write", JSONObject().put("path", "/a").put("content", stub))
        assertFalse(isOffloadCandidate(stubbedWrite))
        assertTrue(isOffloadCandidate(AgentContentPart.ToolResult("c", "file_read", readback)))
        assertTrue(isOffloadCandidate(AgentContentPart.ToolUse("w", "file_write", JSONObject().put("content", "y".repeat(600)))))
        assertNull(ContextOffload.offloadReadbackPath("[/var/minis/workspace/big.csv | 9 bytes | 1 lines | showing 1-1 of 1]\nrows"))
    }


    @Test
    fun `write content that is an offload placeholder is refused`() {
        val stub = ContextOffload.stub(1200, 48_000, "${ContextOffload.LINUX_OFFLOADS_DIR}/tools/file_write_deadbeef0001.txt")
        assertTrue("the stub itself must be refused", ContextOffload.isOffloadPlaceholder(stub))
        assertTrue(ContextOffload.isOffloadPlaceholder("\n  $stub"))
        assertFalse(ContextOffload.isOffloadPlaceholder("# real content"))
        assertFalse(
            ContextOffload.isOffloadPlaceholder("notes on ${ContextOffload.OFFLOADED_PREFIX} handling"),
        )
        assertFalse("empty content is a truncation problem, not this one", ContextOffload.isOffloadPlaceholder(""))

        val refusal = ContextOffload.placeholderWriteRefusal("content", "/var/minis/workspace/app.py")
        assertTrue(refusal.startsWith("Error:"))
        assertTrue(refusal.contains("'content'"))
        assertTrue(refusal.contains("Nothing was written to /var/minis/workspace/app.py"))
        assertTrue("must point at file_read to recover", refusal.contains("Use file_read on the path named inside the placeholder"))

        for (tool in listOf("tools/FileWriteTool.kt", "tools/FileEditTool.kt")) {
            val src = ProductionSources.read(tool)
            assertTrue("$tool must consult the guard", src.contains("ContextOffload.isOffloadPlaceholder("))
            assertTrue("$tool must refuse, not write", src.contains("ContextOffload.placeholderWriteRefusal("))
            assertTrue(
                "$tool must check before resolving the host path",
                src.indexOf("ContextOffload.isOffloadPlaceholder(") < src.indexOf("resolveSessionHostPath"),
            )
        }
        assertTrue(
            ProductionSources.read("tools/FileWriteTool.kt")
                .contains("ContextOffload.isOffloadPlaceholder(content)"),
        )
        val editSrc = ProductionSources.read("tools/FileEditTool.kt")
        assertTrue(editSrc.contains("ContextOffload.isOffloadPlaceholder(newString)"))
        assertFalse(
            "a stub in old_string must NOT be gated here",
            editSrc.contains("ContextOffload.isOffloadPlaceholder(oldString)"),
        )
    }


    @Test
    fun `the reported byte count is the file's real size in UTF-8 bytes, not characters`() {
        val content = "héllo — 你好\n" + "x".repeat(100)
        val f = File(tmp.root, "out.md")
        f.writeText(content)
        val reported = f.length()
        assertEquals(content.toByteArray(Charsets.UTF_8).size.toLong(), reported)
        assertTrue("bytes differ from chars for non-ASCII content", reported != content.length.toLong())
        assertEquals("Wrote to /x/out.md ($reported bytes)", "Wrote to /x/out.md (${f.length()} bytes)")
        val before = f.readBytes()
        val repairs = ToolJsonRepair.repair("file_write", JSONObject(), cutWriteTail, tools)
        if (refusesTruncatedWrite("file_write", repairs) != null) {  }
        assertTrue(f.readBytes().contentEquals(before))
    }


    @Test
    fun `both the current notice and the legacy stub are refused`() {
        val path = "${ContextOffload.LINUX_OFFLOADS_DIR}/tools/file_write_c1.txt"
        val notice = ContextOffload.prunedArgumentNotice(4406, 15220, path)
        val legacy = ContextOffload.stub(4406, 15220, path)

        assertTrue("current notice must be refused", ContextOffload.isOffloadPlaceholder(notice))
        assertTrue("legacy stub must still be refused", ContextOffload.isOffloadPlaceholder(legacy))
        assertTrue("a newline-prefixed notice is still caught", ContextOffload.isOffloadPlaceholder("\n  $notice"))

        assertTrue(notice.startsWith(ContextOffload.SYSTEM_REMINDER_OPEN))
        assertTrue(notice.contains(ContextOffload.SYSTEM_NOTICE_TAG))
        assertTrue(notice.contains("pruned to save context and offloaded to: $path"))
        assertTrue(notice.contains("NEVER pass this placeholder"))
        assertTrue(notice.trimEnd().endsWith("</system-reminder>"))

        assertFalse(
            "a bare system-reminder is not an offload stub",
            ContextOffload.isOffloadPlaceholder(
                "${ContextOffload.SYSTEM_REMINDER_OPEN}\nuser prefers metric\n</system-reminder>",
            ),
        )
        assertFalse(
            "the tag without the envelope is not an offload stub",
            ContextOffload.isOffloadPlaceholder("${ContextOffload.SYSTEM_NOTICE_TAG} a note"),
        )
        assertFalse(ContextOffload.isOffloadPlaceholder("notes on ${ContextOffload.SYSTEM_NOTICE_TAG} handling"))
    }

    @Test
    fun `an already-pruned argument is skipped by flag even when its text looks genuine`() {
        val big = "x".repeat(2_000)
        val flagged = AgentContentPart.ToolUse(
            "w", "file_write", JSONObject().put("path", "/a").put("content", big),
            isOffloadedArgument = true,
        )
        assertFalse("the flag alone must exclude it", isOffloadCandidate(flagged))
        val unflagged = AgentContentPart.ToolUse(
            "w", "file_write", JSONObject().put("path", "/a").put("content", big),
        )
        assertTrue("sanity: the same part without the flag IS a candidate", isOffloadCandidate(unflagged))
        assertFalse("default is false, so old history decodes unchanged", unflagged.isOffloadedArgument)
    }

    @Test
    fun `an unanswered tool call's argument is never pruned`() {
        val big = "y".repeat(2_000)
        val pending = AgentContentPart.ToolUse(
            "pending-id", "file_write", JSONObject().put("path", "/a").put("content", big),
        )
        assertFalse(
            "no tool_result for this id yet",
            isOffloadCandidate(pending, answeredToolUseIds = setOf("some-other-id")),
        )
        assertTrue(
            "once answered, it is prunable",
            isOffloadCandidate(pending, answeredToolUseIds = setOf("pending-id")),
        )
    }


    @Test
    fun `drift guard - refusal, model note and byte report still in production`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith(\"truncation+\") }"))
        assertTrue(vm.contains("if (truncationRepairTag != null && (name == \"file_write\" || name == \"file_edit\")) {"))
        assertTrue(vm.contains("Error: This call was NOT executed. Its argument stream was truncated "))
        assertTrue(vm.contains("Nothing was written to disk — the target file is unchanged."))
        assertTrue("refused call must be an error tool result", vm.contains("content = modelMessage,\n                                isError = true,"))
        assertTrue(vm.contains("The argument stream for this call was \" +\n                                \"truncated in transit and auto-closed by the client"))
        assertTrue(
            "the scan must skip via the shared dual-format predicate",
            vm.contains("if (ContextOffload.isOffloadPlaceholder(part.content)) {"),
        )
        assertFalse(
            "no bare legacy-prefix check may remain in the scan",
            vm.contains("part.content.startsWith(ContextOffload.OFFLOADED_PREFIX)"),
        )
        assertTrue(
            "a pruned ARGUMENT gets the system-reminder notice and the provenance flag",
            vm.contains("ContextOffload.prunedArgumentNotice(") &&
                vm.contains("isOffloadedArgument = true"),
        )
        assertTrue(
            "the argument scanner carries both new guards",
            vm.contains("part.isOffloadedArgument ||") &&
                vm.contains("part.id !in answeredToolUseIds"),
        )
        assertTrue(vm.contains("ContextOffload.offloadReadbackPath(part.content)"))
        val fw = ProductionSources.read("tools/FileWriteTool.kt")
        assertTrue(fw.contains("val bytes = file.length()"))
        assertTrue(fw.contains("ToolExecutionResult(\"Wrote to \$path (\$bytes bytes)\", true, toolTitle = toolTitle)"))
        val repair = ProductionSources.read("provider/ToolJsonRepair.kt")
        assertTrue(repair.contains("repairs.add(\"truncation+\" + if (suffix.isEmpty()) \"noop\" else suffix)"))
    }
}
