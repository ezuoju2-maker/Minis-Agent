package com.openminis.app.agent.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretScanTest {

    @Test
    fun findsOpenAiStyleKey() {
        val hit = SecretScan.first("token = \"sk-abcdEFGH1234567890abcdEFGH\"")
        assertNotNull(hit)
        assertEquals("OpenAI-style key", hit!!.kind)
    }

    @Test
    fun findsAnthropicKeyBeforeOpenAiPattern() {
        val hit = SecretScan.first("sk-ant-api03_abcdefghijklmnopqrstuvwxyz")
        assertEquals("Anthropic key", hit!!.kind)
    }

    @Test
    fun findsGitHubPat() {
        assertNotNull(SecretScan.first("github_pat_11ABCDEFG0abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH"))
        assertNotNull(SecretScan.first("ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789"))
    }

    @Test
    fun findsAwsGoogleSlackStripe() {
        assertEquals("AWS access key", SecretScan.first("AKIAIOSFODNN7REALKEY")?.kind)
        assertEquals("Google API key", SecretScan.first("AIzaSyD-1234567890abcdefghijklmnopqrs")?.kind)
        assertEquals("Slack token", SecretScan.first("xoxb-1234567890-abcdefghij")?.kind)
        assertEquals("Stripe key", SecretScan.first("sk_live_51Habcdefghijklmnop")?.kind)
    }

    @Test
    fun findsPrivateKeyBlock() {
        assertEquals(
            "private key block",
            SecretScan.first("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END---")?.kind,
        )
    }

    @Test
    fun ignoresPlaceholders() {
        assertNull(SecretScan.first("sk-test-abcdefghijklmnopqrstuvwx"))
        assertNull(SecretScan.first("sk-example-key-1234567890abcdefgh"))
        assertNull(SecretScan.first("AKIAxxxxxxxxxxxxxxxx"))
        assertNull(SecretScan.first("sk-your-key-here-abcdefghijkl"))
    }

    @Test
    fun ignoresOrdinaryText() {
        assertNull(SecretScan.first("const key = process.env.OPENAI_API_KEY"))
        assertNull(SecretScan.first(""))
    }

    @Test
    fun previewHidesTheMiddle() {
        val hit = SecretScan.first("sk-abcdefghijklmnopqrstuvwxyz123456")!!
        assertTrue(hit.preview.contains("…"))
        assertTrue(hit.preview.length < 20)
    }

    @Test
    fun capsHitsAtFour() {
        val text = listOf(
            "sk-abcdefghijklmnopqrstuvwxyz123456",
            "AKIAIOSFODNN7REALKEY",
            "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789",
            "xoxb-1234567890-abcdef",
            "AIzaSyD-1234567890abcdefghijklmnopqrs",
        ).joinToString("\n")
        assertTrue(SecretScan.scan(text).size <= 4)
    }

    @Test
    fun guidancePointsAtEnvVars() {
        val hit = SecretScan.first("sk-abcdefghijklmnopqrstuvwxyz123456")!!
        val text = SecretScan.guidance(hit)
        assertTrue(text.contains("environment variable"))
        assertTrue(text.contains("sk-"))
    }
}
