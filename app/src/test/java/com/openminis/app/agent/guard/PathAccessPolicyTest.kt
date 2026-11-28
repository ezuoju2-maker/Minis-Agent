package com.openminis.app.agent.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathAccessPolicyTest {

    @Test
    fun workspaceWritesAreAllowed() {
        val v = PathAccessPolicy.evaluate("/var/minis/workspace/app/main.py")
        assertTrue(v.allowed)
        assertEquals(PathAccessPolicy.Zone.WORKSPACE, v.zone)
    }

    @Test
    fun sharedSkillsAndMemoryAreAllowed() {
        assertEquals(PathAccessPolicy.Zone.SHARED, PathAccessPolicy.evaluate("/var/minis/shared/notes.md").zone)
        assertEquals(PathAccessPolicy.Zone.SKILLS, PathAccessPolicy.evaluate("/var/minis/skills/demo/SKILL.md").zone)
        assertEquals(PathAccessPolicy.Zone.MEMORY, PathAccessPolicy.evaluate("/var/minis/memory/GLOBAL.md").zone)
    }

    @Test
    fun tmpAndRootAreAllowed() {
        assertEquals(PathAccessPolicy.Zone.TEMP, PathAccessPolicy.evaluate("/tmp/build.log").zone)
        assertEquals(PathAccessPolicy.Zone.HOME, PathAccessPolicy.evaluate("/root/.bashrc").zone)
    }

    @Test
    fun etcIsBlocked() {
        val v = PathAccessPolicy.evaluate("/etc/passwd")
        assertTrue(v.blocked)
        assertEquals(PathAccessPolicy.Zone.SYSTEM, v.zone)
        assertTrue(v.reason.contains("protected system path"))
    }

    @Test
    fun procDevAndBootAreBlocked() {
        assertTrue(PathAccessPolicy.evaluate("/proc/self/mem").blocked)
        assertTrue(PathAccessPolicy.evaluate("/dev/block/sda").blocked)
        assertTrue(PathAccessPolicy.evaluate("/boot/vmlinuz").blocked)
    }

    @Test
    fun usrBinIsBlockedButUsrShareTmpIsNotBlockedAsSystemPrefixOnly() {
        assertTrue(PathAccessPolicy.evaluate("/usr/bin/python3").blocked)
        assertFalse(PathAccessPolicy.evaluate("/usr/local/lib/my.so").blocked)
    }

    @Test
    fun prefixMatchIsBoundaryAware() {
        assertFalse(PathAccessPolicy.evaluate("/etcetera/notes.txt").blocked)
        assertFalse(PathAccessPolicy.evaluate("/workspace/scripts").blocked)
    }

    @Test
    fun hostRootfsPrefixIsStripped() {
        val rootfs = "/data/user/0/com.openminis.app/files/ubuntu-rootfs"
        val v = PathAccessPolicy.evaluate("$rootfs/var/minis/workspace/a.txt", rootfs)
        assertEquals(PathAccessPolicy.Zone.WORKSPACE, v.zone)
        assertTrue(v.allowed)
    }

    @Test
    fun hostRootfsSystemPathIsStillBlocked() {
        val rootfs = "/data/user/0/com.openminis.app/files/ubuntu-rootfs"
        assertTrue(PathAccessPolicy.evaluate("$rootfs/etc/hosts", rootfs).blocked)
    }

    @Test
    fun relativePathBecomesAbsolute() {
        assertEquals("/main.py", PathAccessPolicy.normalize("main.py"))
    }

    @Test
    fun duplicateSlashesCollapse() {
        assertEquals("/var/minis/workspace/x.txt", PathAccessPolicy.normalize("/var//minis///workspace/x.txt"))
    }

    @Test
    fun fileUriIsUnwrapped() {
        assertEquals("/var/minis/workspace/x.txt", PathAccessPolicy.normalize("file:///var/minis/workspace/x.txt"))
    }

    @Test
    fun tildeExpandsToRoot() {
        assertEquals("/root/notes.md", PathAccessPolicy.normalize("~/notes.md"))
    }

    @Test
    fun emptyPathIsNotBlocked() {
        assertFalse(PathAccessPolicy.evaluate("   ").blocked)
    }

    @Test
    fun writableRootsMentionsWorkspace() {
        assertTrue(PathAccessPolicy.writableRoots().contains("/var/minis/workspace"))
    }
}
