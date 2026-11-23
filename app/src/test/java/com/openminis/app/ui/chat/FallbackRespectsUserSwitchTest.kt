package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackRespectsUserSwitchTest {

    private val vmSrc by lazy {
        File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
            .also { assertTrue("missing ChatViewModel.kt", it.exists()) }.readText()
    }

    @Test
    fun `fallback may rebind only while the class-level provider is unchanged`() {
        val a = Any()
        val c = Any()
        assertTrue(ChatViewModel.fallbackMayRebindSession(a, a))
        assertFalse("user switched to another provider mid-turn", ChatViewModel.fallbackMayRebindSession(c, a))
        assertTrue(ChatViewModel.fallbackMayRebindSession(null, null))
        assertFalse(ChatViewModel.fallbackMayRebindSession(c, null))
    }

    @Test
    fun `the class-level write and persistBinding sit behind the user-switch guard`() {
        val loop = vmSrc.substringAfter("private suspend fun runAgentLoop(")
        assertTrue(loop.contains("var classProviderAtSend = this@ChatViewModel.currentProvider"))
        val fn = vmSrc.substringAfter("private fun adoptLoopFallbackBinding(").substringBefore("\n    }\n")
        val guard = fn.indexOf("val userSwitchedMidTurn = !fallbackMayRebindSession(currentProvider, classProviderAtSend)")
        val write = fn.indexOf("            currentProvider = next")
        val persist = fn.indexOf("persistBinding(\"\"\"{\"type\":\"entry\",\"entryId\":\"\${newEntry.id}\"}\"\"\")")
        assertTrue("guard not found", guard >= 0)
        assertTrue("class-level write must follow the guard", write > guard)
        assertTrue("persistBinding must follow the guard", persist > guard)
        assertTrue(fn.contains("return !userSwitchedMidTurn"))
        val call = "if (adoptLoopFallbackBinding(nextCandidate, classProviderAtSend, isRealModelChange)) {\n" +
            "                            classProviderAtSend = next"
        assertTrue("error-path call site", loop.contains(call))
        assertTrue(
            "empty-turn call site",
            loop.contains("if (adoptLoopFallbackBinding(emptyNext, classProviderAtSend, isRealModelChange)) {\n" +
                "                            classProviderAtSend = next"),
        )
    }
}
