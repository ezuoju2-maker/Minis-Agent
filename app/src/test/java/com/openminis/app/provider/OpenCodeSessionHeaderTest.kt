package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.openminis.app.ProductionSources
import org.junit.Test

class OpenCodeSessionHeaderTest {

    private val SID = "3f2a1c7e-9b04-4d55-8e21-0a7c6f5b3d19"


    @Test
    fun `the documented endpoint is matched`() {
        assertTrue(OpenCodeSessionHeader.matches("https://opencode.ai/zen/go/v1"))
    }

    @Test
    fun `subdomains of the service are matched`() {
        assertTrue(OpenCodeSessionHeader.matches("https://api.opencode.ai/v1"))
        assertTrue(OpenCodeSessionHeader.matches("https://zen.opencode.ai/go/v1"))
    }

    @Test
    fun `lookalike hosts are not matched`() {
        assertFalse(
            "a subdomain-prefixed lookalike must not match",
            OpenCodeSessionHeader.matches("https://opencode.ai.evil.example.com/v1"),
        )
        assertFalse(
            "a suffix lookalike must not match",
            OpenCodeSessionHeader.matches("https://not-opencode.ai/v1"),
        )
        assertFalse(
            "the domain appearing in a path must not match",
            OpenCodeSessionHeader.matches("https://relay.example.com/opencode.ai/v1"),
        )
    }

    @Test
    fun `ordinary providers are not matched`() {
        for (url in listOf(
            "https://api.openai.com/v1",
            "https://api.deepseek.com/v1",
            "https://generativelanguage.googleapis.com/v1beta",
            "http://192.168.1.10:11434/v1",
        )) {
            assertFalse("$url must not match", OpenCodeSessionHeader.matches(url))
        }
    }

    @Test
    fun `absent or unparseable urls are not matched`() {
        assertFalse(OpenCodeSessionHeader.matches(null))
        assertFalse(OpenCodeSessionHeader.matches(""))
        assertFalse(OpenCodeSessionHeader.matches("   "))
        assertFalse(OpenCodeSessionHeader.matches("not a url"))
    }

    @Test
    fun `a scheme-less url is normalized before matching`() {
        assertTrue(OpenCodeSessionHeader.matches("opencode.ai/zen/go/v1"))
        assertTrue(OpenCodeSessionHeader.matches("api.opencode.ai/v1"))
        assertEquals(
            mapOf("x-opencode-session" to SID),
            OpenCodeSessionHeader.headersFor("opencode.ai/zen/go/v1", SID),
        )
    }

    @Test
    fun `a scheme-less lookalike is still refused`() {
        assertFalse(OpenCodeSessionHeader.matches("not-opencode.ai/v1"))
        assertFalse(OpenCodeSessionHeader.matches("opencode.ai.evil.example.com/v1"))
    }

    @Test
    fun `http is matched as well as https`() {
        assertTrue(OpenCodeSessionHeader.matches("http://opencode.ai/zen/go/v1"))
    }

    @Test
    fun `host casing does not matter`() {
        assertTrue(OpenCodeSessionHeader.matches("https://OpenCode.AI/zen/go/v1"))
    }


    @Test
    fun `a matched host with a real session id gets the header`() {
        assertEquals(
            mapOf("x-opencode-session" to SID),
            OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", SID),
        )
    }

    @Test
    fun `an unmatched host never gets the header`() {
        assertTrue(OpenCodeSessionHeader.headersFor("https://api.openai.com/v1", SID).isEmpty())
        assertTrue(OpenCodeSessionHeader.headersFor("https://not-opencode.ai/v1", SID).isEmpty())
    }


    @Test
    fun `a draft session id is never sent`() {
        for (draft in listOf(
            "__new__3f2a1c7e-9b04-4d55-8e21-0a7c6f5b3d19",
            "__new__abc__fld__work",
            "__new__abc__grp__research",
        )) {
            assertTrue(
                "draft id $draft must not be sent",
                OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", draft).isEmpty(),
            )
        }
    }

    @Test
    fun `any synthetic underscore key is refused`() {
        assertFalse(OpenCodeSessionHeader.isPersistedSessionId("__draft__x"))
        assertFalse(OpenCodeSessionHeader.isPersistedSessionId("__whatever"))
    }

    @Test
    fun `a missing or blank id sends nothing rather than a placeholder`() {
        val url = "https://opencode.ai/zen/go/v1"
        assertTrue(OpenCodeSessionHeader.headersFor(url, null).isEmpty())
        assertTrue(OpenCodeSessionHeader.headersFor(url, "").isEmpty())
        assertTrue(OpenCodeSessionHeader.headersFor(url, "   ").isEmpty())
    }

    @Test
    fun `a real uuid is recognised as persisted`() {
        assertTrue(OpenCodeSessionHeader.isPersistedSessionId(SID))
    }

    @Test
    fun `the same session yields the same header every time`() {
        val url = "https://opencode.ai/zen/go/v1"
        val first = OpenCodeSessionHeader.headersFor(url, SID)
        repeat(5) { assertEquals(first, OpenCodeSessionHeader.headersFor(url, SID)) }
    }

    @Test
    fun `different sessions yield different headers`() {
        val url = "https://opencode.ai/zen/go/v1"
        val a = OpenCodeSessionHeader.headersFor(url, "aaaaaaaa-0000-0000-0000-000000000001")
        val b = OpenCodeSessionHeader.headersFor(url, "bbbbbbbb-0000-0000-0000-000000000002")
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        assertTrue("two conversations must not share an id", a != b)
    }

    @Test
    fun `a padded id is trimmed rather than refused`() {
        assertEquals(
            mapOf("x-opencode-session" to SID),
            OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", "  $SID  "),
        )
    }

    @Test
    fun `the header name is exactly what opencode requires`() {
        assertEquals("x-opencode-session", OpenCodeSessionHeader.HEADER)
    }

    @Test
    fun `regenerate title and minis-model-use pass the chat's session id`() {
        val list = ProductionSources.read("ui/sessions/SessionTitleRegenerator.kt")
        assertTrue(list.contains("providerRepository, context, entry, sessionId = id,"))
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("TitleCandidates.providerFor(providerRepository, context, entry, activeSessionId)"))
        val titles = ProductionSources.read("ui/chat/TitleCandidates.kt")
        assertTrue(titles.contains("sessionId = sessionId, overrides = entry.overrides"))
        val modelUse = ProductionSources.read("sandbox/offload/ModelUseOffloadHandler.kt")
        assertTrue(modelUse.contains("val caller = OffloadCallerSession.resolve(request.sessionId)"))
        assertTrue(modelUse.contains("val callerSid = caller.sid"))
        val create = modelUse.substringAfter("val provider = ProviderFactory.create(").substringBefore("\n        )\n")
        assertTrue(create.contains("sessionId = callerSid, overrides = entry.overrides"))
    }

    @Test
    fun `quick test is its own conversation with a fresh random id`() {
        val sheet = ProductionSources.read("ui/components/QuickTestSheet.kt")
        assertTrue(sheet.contains("sessionId = ProviderFactory.oneOffSessionId(),"))
        assertTrue(ProductionSources.read("provider/ProviderFactory.kt").contains("fun oneOffSessionId(): String = java.util.UUID.randomUUID().toString()"))
        val id = java.util.UUID.randomUUID().toString()
        assertTrue("a random UUID counts as a persisted id", OpenCodeSessionHeader.isPersistedSessionId(id))
        assertEquals(mapOf("x-opencode-session" to id), OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", id))
        assertTrue("other hosts still get nothing", OpenCodeSessionHeader.headersFor("https://api.deepseek.com", id).isEmpty())
    }
}
