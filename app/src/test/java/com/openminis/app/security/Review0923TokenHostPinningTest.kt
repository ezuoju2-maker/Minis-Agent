package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class Review0923TokenHostPinningTest {

    private data class DomainRule(
        val domain: String,
        val includeSubdomains: Boolean,
        val anchors: Set<String>?,
        val cleartext: Boolean?,
    )

    private fun load(): Element {
        val f = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml"),
        ).firstOrNull { it.exists() } ?: fail("network_security_config.xml not found") as Nothing
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
    }

    private fun anchorsOf(el: Element): Set<String>? {
        val ta = el.getElementsByTagName("trust-anchors")
        if (ta.length == 0) return null
        val certs = (ta.item(0) as Element).getElementsByTagName("certificates")
        return (0 until certs.length).map { (certs.item(it) as Element).getAttribute("src") }.toSet()
    }

    private fun rules(root: Element): List<DomainRule> {
        val out = mutableListOf<DomainRule>()
        val dcs = root.getElementsByTagName("domain-config")
        for (i in 0 until dcs.length) {
            val dc = dcs.item(i) as Element
            val ct = dc.getAttribute("cleartextTrafficPermitted").takeIf { it.isNotEmpty() }?.toBoolean()
            val anchors = anchorsOf(dc)
            val ds = dc.getElementsByTagName("domain")
            for (d in 0 until ds.length) {
                val de = ds.item(d) as Element
                out += DomainRule(de.textContent.trim(), de.getAttribute("includeSubdomains") == "true", anchors, ct)
            }
        }
        return out
    }

    private fun match(rules: List<DomainRule>, host: String): DomainRule? =
        rules.filter { r -> host == r.domain || (r.includeSubdomains && host.endsWith("." + r.domain)) }
            .maxByOrNull { it.domain.length }

    private fun effectiveAnchors(root: Element, host: String): Set<String> {
        val base = anchorsOf(root.getElementsByTagName("base-config").item(0) as Element) ?: setOf("system")
        return match(rules(root), host)?.anchors ?: base
    }

    private fun effectiveCleartext(root: Element, host: String): Boolean {
        val base = (root.getElementsByTagName("base-config").item(0) as Element)
            .getAttribute("cleartextTrafficPermitted").let { it.isEmpty() || it.toBoolean() }
        return match(rules(root), host)?.cleartext ?: base
    }

    @Test
    fun `the seven hosts 76e46907f named are system-only (matcher sanity check)`() {
        val root = load()
        for (h in listOf("api.openai.com", "auth.openai.com", "api.anthropic.com", "claude.ai",
            "generativelanguage.googleapis.com", "oauth2.googleapis.com", "openrouter.ai",
            "api.deepseek.com", "api.x.ai", "auth.x.ai")) {
            assertEquals("$h", setOf("system"), effectiveAnchors(root, h))
        }
        assertTrue(effectiveAnchors(root, "llm.lan.example").contains("user"))
    }

    @Test
    fun `BUG every host that receives an OAuth bearer token is system-only`() {
        val root = load()
        val leaking = listOf("chatgpt.com", "github.com", "api.githubcopilot.com", "auth.kimi.com")
            .filter { effectiveAnchors(root, it) != setOf("system") }
        assertTrue("token-bearing hosts still trust user CAs: $leaking", leaking.isEmpty())
    }

    @Test
    fun `BUG a pinned official host does not permit cleartext`() {
        val root = load()
        val cleartext = listOf("api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com")
            .filter { effectiveCleartext(root, it) }
        assertTrue("pinned hosts inherit cleartextTrafficPermitted=true: $cleartext", cleartext.isEmpty())
    }

    @Test
    fun `the OAuth loopback callback still allows cleartext`() {
        val root = load()
        assertTrue(effectiveCleartext(root, "localhost"))
        assertTrue(effectiveCleartext(root, "127.0.0.1"))
    }
}
