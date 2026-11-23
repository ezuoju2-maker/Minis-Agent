package com.openminis.app.auth

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

class Review0923OAuthLoopbackForegroundTest {

    @Test
    fun `every file that starts a loopback callback server gates its exchange on foreground`() {
        val offenders = ProductionSources.allKotlinFiles()
            .filter { it.name != "OAuthCallbackServer.kt" && it.name != "OAuthForegroundGate.kt" }
            .filter { f ->
                val t = f.readText()
                Regex("OAuthCallbackServer\\s*\\(").containsMatchIn(t)
            }
            .filterNot { it.readText().contains("OAuthForegroundGate.awaitForeground(") }
            .map { it.name }
        assertTrue(
            "loopback OAuth flows exchanging the code while Minis may still be background " +
                "(network blocked): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every Anthropic token request carries the CLI fingerprint and the claude_ai host`() {
        val src = ProductionSources.read("auth/ClaudeOAuthManager.kt")
        assertTrue(src.contains("override val tokenURL = \"https://claude.ai/v1/oauth/token\""))
        val builders = Regex("\\.url\\(tokenURL\\)").findAll(src).count()
        val fingerprinted = Regex("\\.url\\(tokenURL\\)\\s*\\n\\s*\\.applyClaudeCliMimicryHeaders\\(\\)")
            .findAll(src).count()
        assertTrue("no token request found", builders > 0)
        assertTrue(
            "every request to tokenURL (exchange AND refresh) must apply the CLI fingerprint " +
                "($fingerprinted of $builders do)",
            builders == fingerprinted,
        )
        val stale = ProductionSources.allKotlinFiles()
            .filter { it.name != "ClaudeCliMimicryHeaders.kt" && it.name != "ClaudeOAuthManager.kt" }
            .filter { it.readText().contains("console.anthropic.com/v1/oauth/token") }
            .map { it.name }
        assertTrue("stale token host in $stale", stale.isEmpty())
    }
}
