package com.openminis.app.eval

import com.openminis.app.agent.SubAgentBudgetPolicy
import com.openminis.app.agent.ToolProgressSignature
import com.openminis.app.tools.CommandRiskPolicy
import com.openminis.app.tools.ToolResultTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnPipelineEval {

    private fun aptLog(step: Int): String = buildString {
        for (i in 0 until 400) append("Get:").append(i).append(" http://deb.example/ubuntu bionic/main amd64 pkg").append(i).append(" [").append(1024 + i).append(" kB]\n")
        append("Progress: [").append("=".repeat(step * 5)).append(" ".repeat(50 - step * 5)).append("] ").append(step * 20).append("%\n")
        append("elapsed ").append(step).append(".5s pid=4321 at 10:0").append(step).append(":11\n")
    }

    @Test
    fun `polling the same install five times never looks like progress`() {
        var previous: String? = null
        for (step in 1..5) {
            val now = aptLog(step)
            if (previous != null) {
                assertFalse("step " + step + " must not look like progress", ToolProgressSignature.hasProgress(previous, now))
            }
            previous = now
        }
    }

    @Test
    fun `a genuinely finished install does look like progress`() {
        assertTrue(ToolProgressSignature.hasProgress(aptLog(5), aptLog(5) + "Setting up pkgs (1.4.9) ... done\n"))
    }

    @Test
    fun `trimmed install log still tells the model it was cut and where the rest lives`() {
        val trimmed = ToolResultTrim.trim(aptLog(5), maxLines = 240, maxChars = 40_000)
        assertTrue(trimmed.wasTrimmed)
        assertTrue(trimmed.text.length <= 40_000)
        val notice = trimmed.noticeWithSpill("/var/minis/offloads/tools/shell_execute_abc.txt")
        assertTrue(notice.contains("kept"))
        assertTrue(notice.contains("full-output-saved"))
        assertTrue(notice.contains("sed -n"))
    }

    @Test
    fun `a failing build keeps the error and the tail`() {
        val lines = ArrayList<String>()
        for (i in 1..900) lines.add("compile unit " + i + " ok")
        lines[450] = "error: unresolved reference: ktx"
        val trimmed = ToolResultTrim.trim(lines.joinToString("\n"), maxLines = 80, maxChars = 6000)
        assertTrue(trimmed.wasTrimmed)
        assertTrue(trimmed.text.contains("error: unresolved reference: ktx"))
        assertTrue(trimmed.text.contains("compile unit 900 ok"))
    }

    @Test
    fun `subagent budget warns at twenty percent left and clamps requests`() {
        assertEquals(60, SubAgentBudgetPolicy.DEFAULT_TURNS)
        assertEquals(12, SubAgentBudgetPolicy.warningLead(60))
        assertTrue(SubAgentBudgetPolicy.shouldWarn(12, 60))
        assertFalse(SubAgentBudgetPolicy.shouldWarn(20, 60))
        assertEquals(200, SubAgentBudgetPolicy.coerceTurns(1000))
        assertEquals(8, SubAgentBudgetPolicy.coerceTurns(1))
    }

    @Test
    fun `danger blocks stay blocked and scoped cleanup stays allowed`() {
        assertEquals(CommandRiskPolicy.Level.BLOCK, CommandRiskPolicy.assess("find / -exec rm -rf {} \\;").level)
        assertEquals(CommandRiskPolicy.Level.BLOCK, CommandRiskPolicy.assess("rm -rf ../..").level)
        assertEquals(CommandRiskPolicy.Level.BLOCK, CommandRiskPolicy.assess("echo x > /etc/hosts").level)
        assertEquals(CommandRiskPolicy.Level.SAFE, CommandRiskPolicy.assess("rm -rf build/cache").level)
    }
}
