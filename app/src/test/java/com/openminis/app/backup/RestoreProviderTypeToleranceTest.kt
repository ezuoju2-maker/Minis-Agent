package com.openminis.app.backup

import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreProviderTypeToleranceTest {

    private fun instance(id: String, type: String, label: String = "P-$id") = """
        {"id":"$id","label":"$label","providerType":"$type",
         "credentialType":"apiKey","isEnabled":true}
    """.trimIndent()

    private fun config(vararg instances: String) =
        """{"instances":[${instances.joinToString(",")}],"modelEntries":[],"modelGroups":[]}"""

    private fun parse(json: String) = BackupImporter.parseProviderConfigLeniently(json)


    @Test
    fun `openAIResponses no longer fails the whole file`() {
        val parsed = parse(
            config(
                instance("a", "openAIResponses"),
                instance("b", "anthropic"),
                instance("c", "gemini"),
                instance("d", "openAI"),
                instance("e", "openRouter"),
                instance("f", "xAI"),
                instance("g", "kimiCode"),
                instance("h", "openAI"),
            ),
        )
        assertEquals("all 8 instances survive", 8, parsed.config.instances.size)
        assertEquals("nothing dropped", 0, parsed.droppedInstances)
    }

    @Test
    fun `openAIResponses decodes to its own type, not a fallback`() {
        val parsed = parse(config(instance("a", "openAIResponses")))
        assertEquals(
            ProviderType.openAIResponses,
            parsed.config.instances.single().providerType,
        )
    }

    @Test
    fun `openAIResponses is usable, antigravity is not`() {
        assertTrue(ProviderType.openAIResponses.isUsable)
        assertTrue(!ProviderType.antigravity.isUsable)
        assertTrue(!ProviderType.unsupported.isUsable)
    }


    @Test
    fun `a type from a newer build imports as unsupported instead of failing`() {
        val parsed = parse(
            config(
                instance("a", "someFutureProvider"),
                instance("b", "anthropic"),
            ),
        )
        assertEquals("both kept", 2, parsed.config.instances.size)
        assertEquals("kept, not dropped", 0, parsed.droppedInstances)
        assertEquals(
            ProviderType.unsupported,
            parsed.config.instances.first { it.id == "a" }.providerType,
        )
        assertEquals(
            ProviderType.anthropic,
            parsed.config.instances.first { it.id == "b" }.providerType,
        )
    }

    @Test
    fun `an unknown type keeps the rest of its instance intact`() {
        val parsed = parse(config(instance("a", "someFutureProvider", label = "My Relay")))
        val kept = parsed.config.instances.single()
        assertEquals("My Relay", kept.label)
        assertEquals("a", kept.id)
    }

    @Test
    fun `every known type still decodes as itself`() {
        for (type in ProviderType.entries) {
            val parsed = parse(config(instance("x", type.name)))
            assertEquals(
                "type ${type.name} must round-trip",
                type,
                parsed.config.instances.single().providerType,
            )
        }
    }


    @Test
    fun `a malformed instance is dropped alone and counted`() {
        val parsed = parse(
            """{"instances":[
                {"id":"bad","providerType":"openAI"},
                ${instance("good", "anthropic")}
            ],"modelEntries":[],"modelGroups":[]}""",
        )
        assertEquals("survivor kept", 1, parsed.config.instances.size)
        assertEquals("good", parsed.config.instances.single().id)
        assertEquals("bad one counted", 1, parsed.droppedInstances)
    }

    @Test
    fun `a non-object array element is dropped alone`() {
        val parsed = parse(
            """{"instances":["garbage", ${instance("good", "openAI")}],
                "modelEntries":[],"modelGroups":[]}""",
        )
        assertEquals(1, parsed.config.instances.size)
        assertEquals(1, parsed.droppedInstances)
    }

    @Test
    fun `an unknown credentialType costs only its own instance`() {
        val parsed = parse(
            """{"instances":[
                {"id":"a","label":"L","providerType":"openAI","credentialType":"futureCred"},
                ${instance("b", "gemini")}
            ],"modelEntries":[],"modelGroups":[]}""",
        )
        assertEquals("sibling survives", 1, parsed.config.instances.size)
        assertEquals("b", parsed.config.instances.single().id)
        assertEquals(1, parsed.droppedInstances)
    }


    @Test
    fun `sibling top-level fields survive an instance rewrite`() {
        val parsed = parse(
            """{"instances":[${instance("a", "openAIResponses")}],
                "modelEntries":[],"modelGroups":[],
                "defaultPrimaryGroupId":"grp-1"}""",
        )
        assertEquals("grp-1", parsed.config.defaultPrimaryGroupId)
    }

    @Test
    fun `a config with no instances array still parses`() {
        val parsed = parse("""{"modelEntries":[],"modelGroups":[]}""")
        assertNotNull(parsed.config)
        assertEquals(0, parsed.droppedInstances)
        assertTrue(parsed.config.instances.isEmpty())
    }

    @Test
    fun `an empty instances array is not an error`() {
        val parsed = parse(config())
        assertTrue(parsed.config.instances.isEmpty())
        assertEquals(0, parsed.droppedInstances)
    }


    @Test
    fun `decoded maps known names and falls back for unknown ones`() {
        assertEquals(ProviderType.openAI, ProviderType.decoded("openAI"))
        assertEquals(ProviderType.openAIResponses, ProviderType.decoded("openAIResponses"))
        assertEquals(ProviderType.antigravity, ProviderType.decoded("antigravity"))
        assertEquals(ProviderType.unsupported, ProviderType.decoded("nonesuch"))
        assertEquals(ProviderType.unsupported, ProviderType.decoded(""))
    }
}
