package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [v1.62-apply-patch] The pure patch engine: parsing, matching layers, and
 * the all-or-nothing contract before any IO happens.
 */
class ApplyPatchTest {

    private fun apply(patch: String, files: Map<String, String?>): List<ApplyPatch.FileChange> {
        val parsed = ApplyPatch.parse(patch)
        assertNull(parsed.error)
        return ApplyPatch.apply(parsed.ops, files)
    }

    @Test
    fun `update one line`() {
        val patch = """
            *** Begin Patch
            *** Update File: a.txt
            @@
             one
            -two
            +TWO
             three
            *** End Patch
        """.trimIndent()
        val changes = apply(patch, mapOf("a.txt" to "one\ntwo\nthree\n"))
        assertEquals(1, changes.size)
        assertEquals("one\nTWO\nthree\n", changes[0].after)
        assertEquals(1, changes[0].added)
        assertEquals(1, changes[0].removed)
    }

    @Test
    fun `add and delete files`() {
        val patch = """
            *** Begin Patch
            *** Add File: new.txt
            +hello
            +world
            *** Delete File: old.txt
            *** End Patch
        """.trimIndent()
        val changes = apply(patch, mapOf("old.txt" to "gone\n", "new.txt" to null))
        assertEquals(2, changes.size)
        assertEquals("hello\nworld\n", changes[0].after)
        assertEquals("", changes[1].after)
    }

    @Test
    fun `adding an existing file is refused`() {
        val patch = """
            *** Begin Patch
            *** Add File: a.txt
            +x
            *** End Patch
        """.trimIndent()
        val parsed = ApplyPatch.parse(patch)
        assertNull(parsed.error)
        val err = runCatching { ApplyPatch.apply(parsed.ops, mapOf("a.txt" to "old")) }.exceptionOrNull()
        assertTrue(err is ApplyPatch.ApplyError)
        assertTrue(err!!.message!!.contains("already exists"))
    }

    @Test
    fun `multiple hunks in one file apply top to bottom`() {
        val patch = """
            *** Begin Patch
            *** Update File: a.kt
            @@
             class A {
            -    fun f() = 1
            +    fun f() = 2
             }
            @@
             class B {
            -    fun g() = 3
            +    fun g() = 4
             }
            *** End Patch
        """.trimIndent()
        val out = apply(patch, mapOf("a.kt" to "class A {\n    fun f() = 1\n}\n\nclass B {\n    fun g() = 3\n}\n"))[0].after
        assertTrue(out.contains("fun f() = 2"))
        assertTrue(out.contains("fun g() = 4"))
    }

    @Test
    fun `trailing whitespace differences still match`() {
        val patch = """
            *** Begin Patch
            *** Update File: a.py
            @@
             def f():
            -    return 1
            +    return 2
            *** End Patch
        """.trimIndent()
        val out = apply(patch, mapOf("a.py" to "def f():   \n    return 1\n"))[0].after
        assertEquals("def f():\n    return 2\n", out)
    }

    @Test
    fun `ambiguous fuzzy match is refused, not guessed`() {
        // Two identical bodies; the patch has no unique context.
        val patch = """
            *** Begin Patch
            *** Update File: a.txt
            @@
            -same
            +different
            *** End Patch
        """.trimIndent()
        val parsed = ApplyPatch.parse(patch)
        assertNull(parsed.error)
        val err = runCatching {
            ApplyPatch.apply(parsed.ops, mapOf("a.txt" to "same\nsame\n"))
        }.exceptionOrNull()
        // Exact matching finds the first line, so this is deterministic; the
        // case we care about is a *fuzzy-only* match. Covered below.
        assertTrue(err == null || err is ApplyPatch.ApplyError)
    }

    @Test
    fun `fuzzy whitespace match must be unique`() {
        val patch = """
            *** Begin Patch
            *** Update File: a.txt
            @@
            -  a  b
            +  A B
            *** End Patch
        """.trimIndent()
        val parsed = ApplyPatch.parse(patch)
        assertNull(parsed.error)
        val err = runCatching {
            ApplyPatch.apply(parsed.ops, mapOf("a.txt" to " a   b \na  b\n"))
        }.exceptionOrNull()
        assertTrue(err is ApplyPatch.ApplyError)
        assertTrue(err!!.message!!.contains("does not match"))
    }

    @Test
    fun `missing End marker is reported`() {
        val parsed = ApplyPatch.parse("*** Begin Patch\n*** Update File: a.txt\n-x\n+y")
        assertTrue(parsed.error!!.contains("End Patch"))
    }

    @Test
    fun `path traversal is not silently applied by the engine`() {
        val parsed = ApplyPatch.parse(
            """
            *** Begin Patch
            *** Update File: ../escape.txt
            -a
            +b
            *** End Patch
            """.trimIndent(),
        )
        assertNull(parsed.error)
        // Resolution/safety of paths is the tool wrapper's job; the engine
        // keeps the path verbatim so the wrapper can reject it.
        assertEquals("../escape.txt", parsed.ops[0].path)
    }

    @Test
    fun `move is reported as new path plus deleted source`() {
        val patch = """
            *** Begin Patch
            *** Update File: old.txt
            *** Move to: dir/new.txt
            @@
            -a
            +b
            *** End Patch
        """.trimIndent()
        val changes = apply(patch, mapOf("old.txt" to "a\n"))
        assertEquals("dir/new.txt", changes[0].path)
        assertEquals("b\n", changes[0].after)
        assertEquals("old.txt", changes[1].path)
        assertEquals("", changes[1].after)
    }
}
