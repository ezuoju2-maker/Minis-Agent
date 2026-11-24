package com.openminis.app.skills.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {

    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun entry(
        id: String = "web-fetch",
        version: String = "1.2.0",
        url: String = "https://raw.githubusercontent.com/ezuoju2-maker/minis-skills/main/web-fetch.zip",
        checksum: String = sha,
        size: Long = 10_000,
    ) = RemoteSkillEntry(id, version, url, checksum, sizeBytes = size)

    @Test
    fun `fresh install is allowed`() {
        val plan = SkillRegistry().plan(entry(), null)
        assertTrue(plan is InstallPlan.Install)
        assertEquals(null, (plan as InstallPlan.Install).replacedVersion)
    }

    @Test
    fun `same version is skipped`() {
        assertTrue(SkillRegistry().plan(entry(), "1.2.0") is InstallPlan.Skip)
    }

    @Test
    fun `upgrade replaces the old version`() {
        val plan = SkillRegistry().plan(entry(version = "1.3.0"), "1.2.0")
        assertTrue(plan is InstallPlan.Install)
        assertEquals("1.2.0", (plan as InstallPlan.Install).replacedVersion)
    }

    @Test
    fun `downgrade is refused`() {
        assertTrue(SkillRegistry().plan(entry(version = "1.1.0"), "1.2.0") is InstallPlan.Reject)
    }

    @Test
    fun `insecure url is refused`() {
        val e = entry(url = "http://raw.githubusercontent.com/x/y.zip")
        assertTrue(SkillRegistry().plan(e, null) is InstallPlan.Reject)
    }

    @Test
    fun `unknown host is refused`() {
        val e = entry(url = "https://evil.example.com/skill.zip")
        val plan = SkillRegistry().plan(e, null)
        assertTrue(plan is InstallPlan.Reject)
        assertTrue((plan as InstallPlan.Reject).reason.contains("untrusted host"))
    }

    @Test
    fun `bad checksum format is refused`() {
        assertTrue(SkillRegistry().plan(entry(checksum = "abc"), null) is InstallPlan.Reject)
    }

    @Test
    fun `oversized payload is refused`() {
        val plan = SkillRegistry().plan(entry(size = 64L * 1024 * 1024), null)
        assertTrue(plan is InstallPlan.Reject)
        assertTrue((plan as InstallPlan.Reject).reason.contains("too large"))
    }

    @Test
    fun `checksum verification matches real bytes`() {
        val reg = SkillRegistry()
        assertTrue(reg.verify("abc".toByteArray(), sha))
        assertFalse(reg.verify("abd".toByteArray(), sha))
    }

    @Test
    fun `version comparison is numeric`() {
        val reg = SkillRegistry()
        assertTrue(reg.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(reg.compareVersions("2.0", "2.0.0") == 0)
        assertTrue(reg.compareVersions("v1.0.1", "1.0.0") > 0)
        assertTrue(reg.compareVersions("0.9", "1.0") < 0)
    }
}
