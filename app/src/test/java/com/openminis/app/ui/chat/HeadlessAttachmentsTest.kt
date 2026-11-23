package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessAttachmentsTest {

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }
    private val runner by lazy { ProductionSources.read("debug/HeadlessChatRunner.kt") }

    @Test fun `the rule - the user's send owns the composer, any other prompt only its own files`() {
        val composer = listOf("draft.png")
        val own = listOf("cli.pdf")
        assertEquals(composer, PromptAttachments.of(ownsComposer = true, own = own, composer = composer))
        assertEquals(own, PromptAttachments.of(ownsComposer = false, own = own, composer = composer))
        assertEquals(emptyList<String>(), PromptAttachments.of(ownsComposer = false, own = emptyList(), composer = composer))
        assertTrue(PromptAttachments.clearsComposer(ownsComposer = true))
        assertFalse(PromptAttachments.clearsComposer(ownsComposer = false))
    }

    private val send by lazy { vm.substringAfter("    private fun sendMessage(").substringBefore("\n    }\n\n") }

    @Test fun `idle path - the files sent and the composer clear follow the rule`() {
        assertTrue(send.contains("val promptAttachments = PromptAttachments.of(ownsComposer = !headless, own = headlessAttachments, composer = _attachments.value)"))
        assertTrue(send.contains("val currentAttachments = promptAttachments\n        if (PromptAttachments.clearsComposer(ownsComposer = !headless)) clearAttachments()"))
        assertTrue("the empty-prompt check counts the prompt's own files", send.contains("if (trimmed.isBlank() && promptAttachments.isEmpty()) return SubmitOutcome.Rejected(\"empty_prompt\")"))
        assertEquals(1, send.split("_attachments.value").size - 1)
        assertEquals(1, send.split("clearAttachments()").size - 1)
        assertTrue(send.contains("if (!headless) _inputText.value = \"\""))
    }

    @Test fun `busy path - a queued headless prompt carries its own files`() {
        assertTrue(send.contains("ownsComposer = !headless,\n                ownAttachments = if (headless) headlessAttachments else emptyList(),"))
        val enqueue = vm.substringAfter("    fun enqueuePrompt(").substringBefore("\n    }\n")
        assertTrue(enqueue.contains("val pendingAttachments = PromptAttachments.of(ownsComposer, ownAttachments, _attachments.value)"))
        assertTrue(enqueue.contains("if (PromptAttachments.clearsComposer(ownsComposer)) clearAttachments()"))
        assertTrue(enqueue.contains("attachments = pendingAttachments,"))
    }

    @Test fun `compaction path - a parked headless prompt keeps its own files and never the composer's`() {
        assertTrue(send.contains("pendingSendHeadless = headless\n                    pendingSendAttachments = if (headless) headlessAttachments else emptyList()"))
        for (fn in listOf("fun compactAndSendPending(", "fun sendPendingWithoutCompacting(")) {
            val body = vm.substringAfter(fn).substringBefore("\n    }\n")
            assertTrue(fn, body.contains("val ownFiles = pendingSendAttachments"))
            assertTrue(fn, body.contains("headless = headless, prefill = prefill, headlessAttachments = ownFiles)"))
        }
        val cancel = vm.substringAfter("fun cancelCompactBeforeSend()").substringBefore("\n    }\n")
        assertTrue(cancel.contains("pendingSendAttachments = emptyList()"))
        assertTrue("only the user's text goes back", cancel.contains("if (!pendingSendHeadless) pendingSendText?.let { _inputText.value = it }"))
    }

    @Test fun `every headless entry is headless and brings only its own files`() {
        assertTrue(vm.contains("sendMessage(text, skipContextCheck = false, headless = true, prefill = prefill, headlessAttachments = attachments)"))
        assertTrue(vm.contains("submitPrompt(com.openminis.app.agent.jobs.HelperRunner.STEER_NUDGE_PROMPT)"))
        assertTrue(vm.contains("childVm.submitPrompt(hr.resumeNotice())"))
        assertTrue(vm.contains("childVm.submitPrompt(hr.childPrompt(args))"))
        assertTrue(vm.contains("val outcome = submitPrompt(text)"))
        assertTrue(vm.contains("sendMessage(text, skipContextCheck = true, headless = true, userAskAnswer = true)"))
        assertFalse(runner.contains("vm.addAttachment("))
        assertTrue(runner.contains("val outcome = vm.submitPrompt(text, prefill, attachments)"))
        assertTrue(vm.contains("sendMessage(text, skipContextCheck = false, headless = false)"))
    }

    @Test fun `the callers of HeadlessChatRunner pass their own files`() {
        val rpc = ProductionSources.read("debug/ChatMutationMethods.kt")
        assertTrue(rpc.contains("attachments = attachments,"))
        val scheduled = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertEquals(2, scheduled.split("attachments = emptyList(),").size - 1)
        val app = ProductionSources.read("MinisApp.kt")
        assertTrue(app.contains("attachments = emptyList(), thinkingLevel = null, wait = false, timeoutMs = 0L,"))
    }

    @Test fun `edit mode - only the user's own send applies the edit`() {
        assertTrue(send.contains("val editingId = if (headless) null else _editingMessageId.value"))
        assertEquals(2, send.split("_editingMessageId.value").size - 1)
        assertTrue(send.contains("if (editingId != null) {\n                truncateBeforeEdit(editingId)"))
    }
}
