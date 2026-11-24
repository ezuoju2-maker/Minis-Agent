package com.openminis.app.eval

import com.openminis.app.agent.approval.ApprovalCoordinator
import com.openminis.app.agent.approval.ApprovalMode
import com.openminis.app.agent.approval.CommandRisk
import com.openminis.app.agent.approval.Resolution
import com.openminis.app.agent.approval.Submission
import com.openminis.app.observability.TraceLog
import com.openminis.app.sandbox.checkpoint.SessionCheckpoint
import com.openminis.app.skills.remote.InstallPlan
import com.openminis.app.skills.remote.RemoteSkillEntry
import com.openminis.app.skills.remote.SkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalCapabilityGoldenTest {

    private val now = 9_000_000L

    @Test
    fun `destructive shell command always reaches the user before running`() {
        val c = ApprovalCoordinator()
        val s = c.submit("rm -rf build", CommandRisk.WARN, now)
        assertTrue(s is Submission.NeedsUser)
        val pending = (s as Submission.NeedsUser).pending
        assertTrue(c.resolve(pending, ApprovalMode.ALLOW_SESSION, now + 1) is Resolution.Approved)
        assertTrue(c.submit("rm -rf build", CommandRisk.WARN, now + 2) is Submission.Run)
    }

    @Test
    fun `blocked command can never be approved`() {
        val c = ApprovalCoordinator()
        assertTrue(c.submit("rm -rf /", CommandRisk.BLOCK, now) is Submission.Refused)
        assertEquals(0, c.openCount())
    }

    @Test
    fun `denied command returns an instruction the model can act on`() {
        val c = ApprovalCoordinator()
        val pending = (c.submit("chmod -R 777 /data", CommandRisk.WARN, now) as Submission.NeedsUser).pending
        val r = c.resolve(pending, ApprovalMode.DENY, now)
        val text = c.feedback(r, CommandRisk.WARN)
        assertTrue(text.contains("Blocked by the user"))
        assertTrue(text.contains("Do not retry"))
        assertTrue(text.contains("safer alternative"))
    }

    @Test
    fun `checkpoint restores every touched file exactly once`() {
        val cp = SessionCheckpoint()
        cp.record(1, "/w/a.txt", "a0", "a1")
        cp.record(2, "/w/a.txt", "a1", "a2")
        cp.record(2, "/w/b.txt", null, "created")
        val plan = cp.restorePlan().associateBy { it.path }
        assertEquals(2, plan.size)
        assertEquals("a0", plan["/w/a.txt"]?.content)
        assertEquals(null, plan["/w/b.txt"]?.content)
    }

    @Test
    fun `trace export is one parseable line per tool call`() {
        val log = TraceLog()
        log.emit(1, "tool", "shell", 120, true, "ok")
        log.emit(1, "tool", "file_read", 4, false, "not found")
        val lines = log.toNdjson().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(lines[1].contains("\"ok\":false"))
        assertEquals(1, log.failures().size)
    }

    @Test
    fun `remote skill install refuses untrusted or downgraded payloads`() {
        val reg = SkillRegistry()
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val good = RemoteSkillEntry(
            id = "web-fetch",
            version = "1.4.0",
            url = "https://raw.githubusercontent.com/ezuoju2-maker/minis-skills/main/web-fetch.zip",
            sha256 = sha,
        )
        assertTrue(reg.plan(good, "1.3.0") is InstallPlan.Install)
        assertTrue(reg.plan(good, "1.4.0") is InstallPlan.Skip)
        assertTrue(reg.plan(good.copy(version = "1.2.0"), "1.3.0") is InstallPlan.Reject)
        assertTrue(reg.plan(good.copy(url = "https://example.com/x.zip"), null) is InstallPlan.Reject)
    }
}
