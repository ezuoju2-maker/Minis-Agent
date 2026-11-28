package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTraversalHardeningTest {

    @Test
    fun keepsOrdinaryPaths() {
        assertEquals("/var/minis/workspace/a.txt", PRootKernel.normalizeLinuxPath("/var/minis/workspace/a.txt"))
        assertEquals("/var/minis/workspace/a.txt", PRootKernel.normalizeLinuxPath("/var/minis//workspace/./a.txt"))
        assertEquals("/", PRootKernel.normalizeLinuxPath("/"))
    }

    @Test
    fun resolvesParentSegmentsLexically() {
        assertEquals("/var/minis/workspace/a.txt", PRootKernel.normalizeLinuxPath("/var/minis/shared/../workspace/a.txt"))
        assertEquals("/etc/passwd", PRootKernel.normalizeLinuxPath("/../../etc/passwd"))
    }

    @Test
    fun refusesEscapesAboveTheRoot() {
        assertNull(PRootKernel.normalizeLinuxPath("/.."))
        assertNull(PRootKernel.normalizeLinuxPath("/a/../.."))
        assertNull(PRootKernel.normalizeLinuxPath("relative/path"))
        assertNull(PRootKernel.normalizeLinuxPath(""))
    }

    @Test
    fun hostResolutionRejectsTraversal() {
        val before = PRootKernel.bindMounts.toMap()
        PRootKernel.bindMounts["/var/minis/shared"] = "/tmp/minis-shared-probe"
        try {
            assertNull(PRootKernel.resolveHostPath("/var/minis/shared/../../../../data/data/x"))
            assertNull(PRootKernel.resolveHostPath("/var/minis/shared/../../"))
            val ok = PRootKernel.resolveHostPath("/var/minis/shared/../shared/note.md")
            assertTrue(ok != null)
            assertEquals("/tmp/minis-shared-probe/note.md", ok!!.absolutePath)
        } finally {
            PRootKernel.bindMounts.clear()
            PRootKernel.bindMounts.putAll(before)
        }
    }
}
