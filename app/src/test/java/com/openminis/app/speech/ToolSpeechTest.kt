package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSpeechTest {

    @Test
    fun `shell uses the model's title when it supplied one`() {
        assertEquals(
            "Executing script: install deps",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py3-pip"}""", "install deps"),
        )
    }

    @Test
    fun `shell falls back to the command`() {
        assertEquals(
            "Executing script: apk add py3-pip",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py3-pip"}""", null),
        )
    }

    @Test
    fun `a chinese title switches the whole phrase to chinese`() {
        assertEquals(
            "正在执行脚本:安装依赖包",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py3-pip"}""", "安装依赖包"),
        )
        assertEquals(
            "正在读取文件:配置文件",
            ToolSpeech.announcement("file_read", """{"path":"/etc/conf"}""", "配置文件"),
        )
    }

    @Test
    fun `file tools speak the file name, not the whole path`() {
        assertEquals(
            "Reading file: notes.md",
            ToolSpeech.announcement("file_read", """{"path":"/var/minis/shared/notes.md"}""", null),
        )
        assertEquals(
            "Writing file: out.txt",
            ToolSpeech.announcement("file_write", """{"path":"/root/out.txt"}""", null),
        )
        assertEquals(
            "Editing file: Main.kt",
            ToolSpeech.announcement("file_edit", """{"path":"/src/Main.kt"}""", null),
        )
    }

    @Test
    fun `a missing path degrades to a generic noun`() {
        assertEquals("Reading file: a file", ToolSpeech.announcement("file_read", "{}", null))
        assertEquals("正在读取文件:读取", ToolSpeech.announcement("file_read", "{}", "读取"))
    }

    @Test
    fun `any non-blank title becomes the detail and drives the language`() {
        assertEquals("Reading file: 。", ToolSpeech.announcement("file_read", "{}", "。"))
        assertEquals("正在读取文件:读", ToolSpeech.announcement("file_read", "{}", "读"))
    }

    @Test
    fun `browser and image tools use their own phrasing`() {
        assertEquals(
            "Using browser: click login",
            ToolSpeech.announcement("browser_use", """{"action":"click login"}""", null),
        )
        assertEquals(
            "Reading image: shot.png",
            ToolSpeech.announcement("read_image", """{"path":"/tmp/shot.png"}""", null),
        )
    }

    @Test
    fun `an unrecognised tool falls back to its name`() {
        assertEquals("Running: some_new_tool", ToolSpeech.announcement("some_new_tool", "{}", null))
        assertEquals("Running: fetch the weather",
            ToolSpeech.announcement("some_new_tool", "{}", "fetch the weather"))
    }

    @Test
    fun `an over-long detail is clipped with an ellipsis`() {
        val long = "a".repeat(100)
        val out = ToolSpeech.announcement("shell_execute", """{"command":"$long"}""", null)
        assertTrue("should end with an ellipsis: $out", out.endsWith("…"))
        assertEquals("Executing script: " + "a".repeat(40) + "…", out)
    }

    @Test
    fun `malformed or partial json does not throw`() {
        assertEquals(
            "Executing script: ",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py""", null),
        )
        assertEquals("Reading file: a file", ToolSpeech.announcement("file_read", "not json", null))
        assertEquals("Executing script: ", ToolSpeech.announcement("shell_execute", null, null))
    }

    @Test
    fun `blank and null-literal titles fall through to the argument`() {
        assertEquals(
            "Executing script: ls -la",
            ToolSpeech.announcement("shell_execute", """{"command":"ls -la"}""", "   "),
        )
        assertEquals("Reading file: a file", ToolSpeech.announcement("file_read", """{"other":1}""", null))
    }

    @Test
    fun `a trailing slash does not produce an empty file name`() {
        assertEquals(
            "Reading file: dir",
            ToolSpeech.announcement("file_read", """{"path":"/var/dir/"}""", null),
        )
    }
}
