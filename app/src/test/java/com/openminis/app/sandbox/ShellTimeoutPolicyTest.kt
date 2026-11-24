package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTimeoutPolicyTest {

    @Test
    fun `plain listing stays quick`() {
        assertEquals(ShellTimeoutPolicy.QUICK_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("ls -la /tmp"))
        assertEquals(ShellTimeoutPolicy.QUICK_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("cat build.log"))
    }

    @Test
    fun `chained command takes the widest timeout`() {
        assertEquals(ShellTimeoutPolicy.BUILD_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("cd /opt && make -j4"))
        assertEquals(ShellTimeoutPolicy.INSTALL_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("export CI=1; npm install"))
    }

    @Test
    fun `heavy tree walks are not quick`() {
        assertEquals(ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("find / -name *.so"))
        assertEquals(ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("du -sh /data"))
        assertEquals(ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("rm -rf /data/tmp"))
    }

    @Test
    fun `recursive grep is not quick`() {
        assertEquals(ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("grep -r ERROR /var/log"))
        assertEquals(ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("grep --recursive ERROR /var/log"))
    }

    @Test
    fun `network keeps its budget`() {
        assertEquals(ShellTimeoutPolicy.NETWORK_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("curl -sL https://example.com"))
        assertEquals(ShellTimeoutPolicy.NETWORK_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("scp -r host:/a /b"))
    }

    @Test
    fun `empty command falls back to default`() {
        assertEquals(ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("   "))
    }
}
