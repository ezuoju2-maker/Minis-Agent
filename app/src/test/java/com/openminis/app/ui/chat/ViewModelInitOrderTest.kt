package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViewModelInitOrderTest {

    private fun source(): List<String> {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("ChatViewModel.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        return f.readLines()
    }

    private fun initLine(lines: List<String>): Int {
        val idx = lines.indexOfFirst { it.trimEnd() == "    init {" }
        assertTrue("no class-body `init {` found in ChatViewModel", idx >= 0)
        return idx + 1
    }

    private fun declarationLine(lines: List<String>, name: String): Int {
        val idx = lines.indexOfFirst {
            Regex("""^\s*(?:private |internal )?(?:val|var)\s+$name\b""").containsMatchIn(it)
        }
        assertTrue("property `$name` not found in ChatViewModel", idx >= 0)
        return idx + 1
    }

    @Test
    fun `recentlyFailedEntryIds is declared before init`() {
        val lines = source()
        val decl = declarationLine(lines, "recentlyFailedEntryIds")
        val init = initLine(lines)
        assertTrue(
            "recentlyFailedEntryIds is declared at line $decl, BELOW `init` at line $init — " +
                "loadSession() reads it synchronously, so it will be null and the " +
                "ViewModel constructor will throw NPE on every launch",
            decl < init,
        )
    }

    @Test
    fun `every property the synchronous load path reads is declared before init`() {
        val lines = source()
        val init = initLine(lines)
        val text = lines.joinToString("\n")

        val scope = buildString {
            for (fn in listOf("loadSession", "resolveProviderFromGroup", "applyGroupSessionDefaults")) {
                val m = Regex("""\n\s*(?:private |internal )?(?:suspend )?fun $fn\(""").find(text)
                if (m != null) {
                    var depth = 0
                    var i = text.indexOf('{', m.range.first)
                    val start = i
                    while (i < text.length) {
                        if (text[i] == '{') depth++
                        else if (text[i] == '}') { depth--; if (depth == 0) break }
                        i++
                    }
                    append(text.substring(start, minOf(i + 1, text.length)))
                }
            }
        }
        assertTrue("could not extract the load path from the source", scope.length > 200)

        val classEnd = (init until lines.size).firstOrNull { lines[it].trimEnd() == "}" }
        assertTrue("end of the ChatViewModel class body not found", classEnd != null)
        val offenders = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val lineNo = i + 1
            if (lineNo <= init) continue
            if (i >= classEnd!!) break
            val m = Regex("""^    (?:private |internal )?(?:val|var) (\w+)\s*(?::[^=]+)?=""")
                .find(line) ?: continue
            if ("by lazy" in line) continue
            val name = m.groupValues[1]
            if (Regex("""\b$name\b""").containsMatchIn(scope)) {
                offenders.add("$name (line $lineNo)")
            }
        }

        assertTrue(
            "these properties are declared BELOW `init` (line $init) yet are read on " +
                "loadSession()'s synchronous path — each will be null/default when the " +
                "constructor runs: $offenders",
            offenders.isEmpty(),
        )
    }
}
