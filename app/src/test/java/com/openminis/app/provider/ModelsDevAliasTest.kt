package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevAliasTest {

    private fun candidates(id: String) = ModelsDevApi.aliasCandidates(id)

    @Test
    fun `the reported suffix alias reaches its catalogued base`() {
        val c = candidates("glm-5.3-flash-cpa")
        assertTrue("must reach the catalogued base id: $c", c.contains("glm-5.3-flash"))
    }

    @Test
    fun `the exact id is always tried first`() {
        assertEquals("glm-5.3-flash-cpa", candidates("glm-5.3-flash-cpa").first())
        assertEquals("gpt-5-codex", candidates("gpt-5-codex").first())
    }

    @Test
    fun `candidates are ordered longest first`() {
        assertEquals(listOf("a-b-c", "a-b"), candidates("a-b-c"))
    }

    @Test
    fun `the walk stops before a bare generic head`() {
        val c = candidates("glm-5.3-flash-cpa")
        assertFalse("must not degrade to a bare vendor head: $c", c.contains("glm"))
        assertFalse(candidates("gpt-5-codex-preview").contains("gpt"))
    }

    @Test
    fun `a two segment id does not shorten at all`() {
        assertEquals(listOf("gpt-5"), candidates("gpt-5"))
        assertEquals(listOf("claude-opus"), candidates("claude-opus"))
    }

    @Test
    fun `a prefixed id keeps working unchanged`() {
        assertEquals("cpa-glm-5.3", candidates("cpa-glm-5.3").first())
    }

    @Test
    fun `colon separated ids are handled`() {
        val c = candidates("openai:gpt-5-preview")
        assertTrue("colon must be a separator too: $c", c.contains("openai:gpt-5"))
    }

    @Test
    fun `degenerate inputs terminate and stay inert`() {
        assertEquals(listOf(""), candidates(""))
        assertEquals(listOf("gpt"), candidates("gpt"))
        assertTrue(candidates("-x-y").isNotEmpty())
        assertTrue(candidates("---").isNotEmpty())
    }

    @Test
    fun `no candidate is ever longer than the input`() {
        for (id in listOf("a-b-c-d-e", "openai:gpt-5-codex-preview", "glm-5.3-flash-cpa")) {
            assertTrue(candidates(id).all { it.length <= id.length })
        }
    }


    @Test
    fun `an id with two dots keeps both, shortening only on hyphens`() {
        val c = candidates("qwen3.5-max.preview-cpa")
        assertEquals("qwen3.5-max.preview-cpa", c.first())
        assertTrue("must reach the hyphen-trimmed base: $c", c.contains("qwen3.5-max.preview"))
        assertFalse("a dot is not a cut point: $c", c.any { it == "qwen3" })
        assertTrue("no candidate may lose a dot: $c", c.all { it.count { ch -> ch == '.' } >= 1 })
    }

    @Test
    fun `a dated snapshot suffix is shortened away one segment at a time`() {
        val c = candidates("claude-sonnet-4-6-20260115")
        assertEquals("claude-sonnet-4-6-20260115", c.first())
        assertTrue("must reach the undated id: $c", c.contains("claude-sonnet-4-6"))
        assertTrue(c.contains("claude-sonnet-4"))
        assertTrue(c.contains("claude-sonnet"))
        assertFalse("must never degrade to `claude`: $c", c.contains("claude"))
    }

    @Test
    fun `a vendor slash prefix is preserved, never cut`() {
        val c = candidates("anthropic/claude-sonnet-4-6-20260115")
        assertEquals("anthropic/claude-sonnet-4-6-20260115", c.first())
        assertTrue("must reach the namespaced undated id: $c",
            c.contains("anthropic/claude-sonnet-4-6"))
        assertTrue("every candidate keeps the vendor prefix: $c",
            c.all { it.startsWith("anthropic/") })
    }

    @Test
    fun `a colon vendor prefix IS a cut point, unlike a slash`() {
        val colon = candidates("openai:gpt-5-codex-preview")
        assertTrue("colon shortens: $colon", colon.contains("openai:gpt-5-codex"))
        assertTrue(colon.contains("openai:gpt-5"))

        val slash = candidates("openai/gpt-5-codex-preview")
        assertTrue("slash never splits: $slash", slash.all { it.startsWith("openai/gpt-5") })
    }

    @Test
    fun `the segment floor counts across both separators`() {
        assertTrue(candidates("openai:gpt-5-codex").contains("openai:gpt-5"))
        assertEquals(listOf("gpt-5"), candidates("gpt-5"))
        assertEquals(listOf("openai:gpt"), candidates("openai:gpt"))
    }

    @Test
    fun `candidates are unique and strictly shrinking`() {
        for (id in listOf(
            "claude-sonnet-4-6-20260115",
            "anthropic/claude-sonnet-4-6-20260115",
            "qwen3.5-max.preview-cpa",
            "openai:gpt-5-codex-preview",
            "a-b-c-d-e-f-g",
        )) {
            val c = candidates(id)
            assertEquals("duplicate candidates for $id: $c", c.size, c.distinct().size)
            for (i in 1 until c.size) {
                assertTrue(
                    "candidate $i is not shorter than ${i - 1} for $id: $c",
                    c[i].length < c[i - 1].length,
                )
            }
        }
    }


    @Test
    fun `a third-party endpoint is unrecognized and a first-party one is not`() {
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Custom"))
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Some Relay"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("Anthropic"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("OpenAI"))
    }
}
