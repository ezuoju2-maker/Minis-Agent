package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptJavaLineTest {
    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test
    fun `the JDK line follows the Python note once and mentions no JAVA_TOOL_OPTIONS`() {
        val line = "- Java: install a JDK with `apt-get install -y openjdk-17-jdk` (openjdk-21-jdk / openjdk-11-jdk for other projects). " +
            "javac needs the `-d` output directory to exist.\n"
        assertEquals(1, vm.split(line).size - 1)
        val python = vm.indexOf("- Python packages: Ubuntu 24.04 aarch64 has manylinux wheels")
        val java = vm.indexOf(line)
        assertTrue(python in 0 until java)
        assertFalse(vm.substring(python, java).contains("\n- "))
        assertFalse(vm.contains("JAVA_TOOL_OPTIONS"))
    }
}
