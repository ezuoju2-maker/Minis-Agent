package com.openminis.app.agent.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxGuardTest {

    private fun verdict(mode: SandboxMode, cmd: String) = SandboxGuard.assess(mode, cmd)

    @Test
    fun fullModeNeverBlocks() {
        assertTrue(verdict(SandboxMode.FULL, "rm -rf /etc/x").allowed)
        assertTrue(verdict(SandboxMode.FULL, "echo hi > out.txt").allowed)
    }

    @Test
    fun readOnlyRefusesWriters() {
        assertTrue(verdict(SandboxMode.READ_ONLY, "rm -rf build").blocked)
        assertTrue(verdict(SandboxMode.READ_ONLY, "echo hi > notes.txt").blocked)
        assertTrue(verdict(SandboxMode.READ_ONLY, "npm install").blocked)
        assertTrue(verdict(SandboxMode.READ_ONLY, "git checkout .").blocked)
        assertTrue(verdict(SandboxMode.READ_ONLY, "sed -i 's/a/b/' f.txt").blocked)
        assertTrue(verdict(SandboxMode.READ_ONLY, "mkdir -p out").blocked)
    }

    @Test
    fun readOnlyAllowsReads() {
        assertTrue(verdict(SandboxMode.READ_ONLY, "cat README.md").allowed)
        assertTrue(verdict(SandboxMode.READ_ONLY, "ls -la /var/minis/workspace").allowed)
        assertTrue(verdict(SandboxMode.READ_ONLY, "grep -rn TODO src").allowed)
        assertTrue(verdict(SandboxMode.READ_ONLY, "git status").allowed)
        assertTrue(verdict(SandboxMode.READ_ONLY, "git diff HEAD").allowed)
    }

    @Test
    fun workspaceWriteRefusesSystemTargets() {
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "rm /etc/hosts").blocked)
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "cp a /usr/local/bin/x").blocked)
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "echo x > /var/lib/db").blocked)
    }

    @Test
    fun workspaceWriteAllowsWorkspaceTargets() {
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "rm /var/minis/workspace/a.txt").allowed)
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "echo x > /tmp/log").allowed)
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "cp a.txt /root/b.txt").allowed)
    }

    @Test
    fun workspaceWriteAllowsRelativeTargets() {
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "echo hi > out.txt").allowed)
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "python build.py").allowed)
    }

    @Test
    fun workspaceWriteLeavesReadsAlone() {
        assertTrue(verdict(SandboxMode.WORKSPACE_WRITE, "cat /etc/os-release").allowed)
    }

    @Test
    fun emptyCommandIsAllowed() {
        assertTrue(verdict(SandboxMode.READ_ONLY, "   ").allowed)
    }

    @Test
    fun pathZonesAreClassified() {
        assertTrue(SandboxGuard.isWorkspacePath("/var/minis/workspace/x"))
        assertTrue(SandboxGuard.isWorkspacePath("/tmp/x"))
        assertFalse(SandboxGuard.isWorkspacePath("/etc/x"))
        assertTrue(SandboxGuard.isSystemPath("/usr/bin/x"))
        assertFalse(SandboxGuard.isSystemPath("/var/minis/workspace"))
    }

    @Test
    fun modeParsingAcceptsAliases() {
        assertEquals(SandboxMode.READ_ONLY, SandboxMode.parse("read-only"))
        assertEquals(SandboxMode.WORKSPACE_WRITE, SandboxMode.parse("workspace_write"))
        assertEquals(SandboxMode.FULL, SandboxMode.parse("danger-full-access"))
        assertNull(SandboxMode.parse("nonsense"))
        assertNull(SandboxMode.parse(null))
    }
}
