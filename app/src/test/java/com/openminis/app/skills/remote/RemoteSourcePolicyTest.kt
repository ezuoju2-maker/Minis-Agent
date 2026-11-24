package com.openminis.app.skills.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSourcePolicyTest {

    private val policy = RemoteSourcePolicy()

    @Test
    fun `github urls are allowed`() {
        assertTrue(policy.check("https://raw.githubusercontent.com/ezuoju2-maker/minis-skills/main/x/SKILL.md") is SourceVerdict.Allowed)
        assertTrue(policy.check("https://github.com/ezuoju2-maker/minis-skills") is SourceVerdict.Allowed)
        assertTrue(policy.check("https://api.github.com/repos/a/b/contents/x") is SourceVerdict.Allowed)
    }

    @Test
    fun `plain http is refused`() {
        assertTrue(policy.check("http://github.com/a/b") is SourceVerdict.Rejected)
    }

    @Test
    fun `unknown host is refused`() {
        val v = policy.check("https://evil.example.com/a.zip")
        assertTrue(v is SourceVerdict.Rejected)
        assertTrue((v as SourceVerdict.Rejected).reason.contains("untrusted host"))
    }

    @Test
    fun `suffix lookalike host is refused`() {
        assertTrue(policy.check("https://github.com.evil.example/a") is SourceVerdict.Rejected)
    }

    @Test
    fun `localhost and private literals are refused`() {
        assertTrue(policy.check("https://localhost/a") is SourceVerdict.Rejected)
        assertTrue(policy.check("https://127.0.0.1/a") is SourceVerdict.Rejected)
        assertTrue(policy.check("https://192.168.1.10/a") is SourceVerdict.Rejected)
        assertTrue(policy.check("https://10.0.0.5/a") is SourceVerdict.Rejected)
        assertTrue(policy.check("https://172.20.0.1/a") is SourceVerdict.Rejected)
    }

    @Test
    fun `credentials and odd ports are refused`() {
        assertTrue(policy.check("https://user:pass@github.com/a") is SourceVerdict.Rejected)
        assertTrue(policy.check("https://github.com:8443/a") is SourceVerdict.Rejected)
        assertTrue(policy.check("https://github.com:443/a") is SourceVerdict.Allowed)
    }

    @Test
    fun `oversized or empty urls are refused`() {
        assertTrue(policy.check("") is SourceVerdict.Rejected)
        assertFalse(policy.isAllowed("https://github.com/" + "a".repeat(3000)))
    }

    @Test
    fun `non https schemes are refused`() {
        assertTrue(policy.check("file:///etc/passwd") is SourceVerdict.Rejected)
        assertTrue(policy.check("ftp://github.com/a") is SourceVerdict.Rejected)
    }
}
