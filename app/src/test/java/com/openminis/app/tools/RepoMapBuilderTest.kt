package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepoMapBuilderTest {

    private fun tempDir(): File = Files.createTempDirectory("repomap").toFile()

    private fun write(root: File, path: String, text: String) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    @Test
    fun `extracts kotlin declarations with line numbers`() {
        val dir = tempDir()
        write(
            dir, "src/Core.kt",
            """
            package demo

            data class User(val id: Int)

            object Repo {
                fun load(): User = User(1)
                private suspend fun fetch(id: Int): User = User(id)
            }

            interface Store
            """.trimIndent(),
        )
        val map = RepoMapBuilder.build(dir)
        assertTrue(map.contains("class User:3"))
        assertTrue(map.contains("fun load:6") || map.contains("fun load"))
        assertTrue(map.contains("interface Store"))
        assertFalse("file bodies must not be copied", map.contains("val id: Int"))
    }

    @Test
    fun `imported files rank above unreferenced ones`() {
        val dir = tempDir()
        write(dir, "Bar.kt", "class Bar {\n    fun a() {}\n    fun b() {}\n}\n")
        write(dir, "Main.kt", "import demo.Bar\n\nfun main() {\n}\n")
        write(dir, "Orphan.kt", "class Orphan {\n    fun z() {}\n}\n")
        val map = RepoMapBuilder.build(dir)
        val bar = map.indexOf("Bar.kt")
        val orphan = map.indexOf("Orphan.kt")
        assertTrue(bar >= 0)
        assertTrue("imported Bar should come before unimported Orphan", bar < orphan)
    }

    @Test
    fun `respects the character budget and says what was omitted`() {
        val dir = tempDir()
        repeat(40) { i ->
            write(dir, "f$i.kt", "class C$i {\n" + "    fun m$i() {}\n".repeat(6) + "}\n")
        }
        val map = RepoMapBuilder.build(
            dir,
            RepoMapBuilder.Options(maxChars = 900, maxFiles = 100),
        )
        assertTrue("map must not exceed budget by more than one header", map.length <= 1100)
        assertTrue(map.contains("omitted"))
    }

    @Test
    fun `skips build output and vendored dependencies`() {
        val dir = tempDir()
        write(dir, "src/A.kt", "class A\n")
        write(dir, "build/generated/X.kt", "class X\n")
        write(dir, "node_modules/pkg/index.js", "function nope() {}\n")
        val map = RepoMapBuilder.build(dir)
        assertTrue(map.contains("A.kt"))
        assertFalse(map.contains("X.kt"))
        assertFalse(map.contains("index.js"))
    }

    @Test
    fun `empty tree is reported honestly`() {
        val dir = tempDir()
        val map = RepoMapBuilder.build(dir)
        assertTrue(map.startsWith("repo_map: no supported source files"))
    }

    @Test
    fun `python and typescript outlines`() {
        assertEquals("class", RepoMapBuilder.declarationOf("class Service:", "python")?.kind)
        assertEquals("Service", RepoMapBuilder.declarationOf("class Service:", "python")?.name)
        assertEquals("fun", RepoMapBuilder.declarationOf("async def run(x):", "python")?.kind)
        assertEquals("interface", RepoMapBuilder.declarationOf("export interface Props {", "ts")?.kind)
        assertEquals("fun", RepoMapBuilder.declarationOf("export const load = async () => {", "ts")?.kind)
    }
}
