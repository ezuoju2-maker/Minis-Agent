package com.openminis.app.crash

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeAbortMessageCaptureTest {

    private val src: String by lazy {
        val javaRoot = ProductionSources.mainRoot()
            ?: error("production source root not found")
        val cpp = File(javaRoot.parentFile.parentFile.parentFile.parentFile, "cpp/crash_handler.cpp")
        require(cpp.isFile) { "crash_handler.cpp missing: ${cpp.absolutePath}" }
        cpp.readText()
    }

    @Test
    fun `the liblog aborter hook is installed`() {
        assertTrue(
            "__android_log_set_aborter is the supported way to see a LOG_ALWAYS_FATAL message",
            src.contains("__android_log_set_aborter"),
        )
        assertTrue(
            "resolved via dlsym because minSdk 26 < the symbol's __INTRODUCED_IN(30)",
            src.contains("dlsym(RTLD_DEFAULT, \"__android_log_set_aborter\")"),
        )
    }

    @Test
    fun `the bionic global is read as the fallback`() {
        assertTrue(src.contains("__abort_message"))
    }

    @Test
    fun `the non-public read is guarded at every step`() {
        assertTrue("null slot", src.contains("if (slot == nullptr) return nullptr;"))
        assertTrue("null block", src.contains("if (block == nullptr) return nullptr;"))
        assertTrue("sane size", src.contains("block->size > 64 * 1024"))
    }

    @Test
    fun `the message is written into the report`() {
        assertTrue(src.contains("\"\\nAbort message: \""))
    }

    @Test
    fun `the read happens after the summary is already on disk`() {
        val summaryWrite = src.indexOf("written += w;")
        val abortRead = src.indexOf("const char* amsg = abort_message_or_null();")
        assertTrue("summary write not found", summaryWrite > 0)
        assertTrue("abort message call site not found", abortRead > 0)
        assertTrue(
            "abort message must be read AFTER the summary is flushed",
            abortRead > summaryWrite,
        )
    }

    @Test
    fun `the advice text survives as the fallback`() {
        assertTrue(src.contains("(none captured)"))
        assertTrue(src.contains("exitinfo-*.log"))
    }
}
