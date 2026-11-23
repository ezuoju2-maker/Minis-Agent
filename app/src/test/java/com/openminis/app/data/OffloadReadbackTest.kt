package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OffloadReadbackTest {

    private val dir = ContextOffload.LINUX_OFFLOADS_DIR

    @Test
    fun `a plain file_read of an offloaded file is recognised`() {
        val content = "[$dir/tools/shell_ab12cd.txt | 456789 bytes | 1200 lines | " +
            "showing 1-1200 of 1200]\nthe original large output…"
        assertEquals("$dir/tools/shell_ab12cd.txt", ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `the truncated header variant is recognised`() {
        val content = "[$dir/tools/shell_ab12cd.txt | 999999 bytes | 5000 lines | " +
            "showing 1-800 of 5000 | truncated at 60000 chars, next_offset=801]\nbody"
        assertEquals("$dir/tools/shell_ab12cd.txt", ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `a file_read of an ordinary file is not a readback`() {
        val content = "[/var/minis/workspace/data.csv | 900000 bytes | 20000 lines | " +
            "showing 1-20000 of 20000]\nrows…"
        assertNull(ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `a sibling directory sharing the prefix does not match`() {
        val content = "[${dir}-backup/tools/x.txt | 10 bytes | 1 lines | showing 1-1 of 1]\nx"
        assertNull(ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `the offloads dir itself is accepted`() {
        val content = "[$dir | 10 bytes | 1 lines | showing 1-1 of 1]\nx"
        assertEquals(dir, ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `content that is not a file_read header is not a readback`() {
        assertNull(ContextOffload.offloadReadbackPath("just some tool output"))
        assertNull(ContextOffload.offloadReadbackPath(""))
        assertNull(
            ContextOffload.offloadReadbackPath(
                ContextOffload.stub(100, 200, "$dir/tools/x.txt"),
            ),
        )
    }

    @Test
    fun `a malformed header does not throw`() {
        assertNull(ContextOffload.offloadReadbackPath("["))
        assertNull(ContextOffload.offloadReadbackPath("[]"))
        assertNull(ContextOffload.offloadReadbackPath("[ | 5 bytes]"))
        assertNull(ContextOffload.offloadReadbackPath("[no-close-bracket $dir/x"))
    }

    @Test
    fun `the stub that replaces a readback stays under the rescan floor`() {
        val stub = ContextOffload.stub(12345, 456789, "$dir/tools/shell_ab12cd.txt")
        org.junit.Assert.assertTrue(
            "stub must stay well under the 500-char offload floor, was ${stub.length}",
            stub.length < 500,
        )
    }
}
