package com.openminis.app.data

import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.SubAgentDefinition
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentPersistenceRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun configWithRoster(): ProviderConfig {
        val cfg = ProviderConfig()
        cfg.subAgents.add(SubAgentDefinition.makeBuiltIn())
        cfg.subAgents.add(
            SubAgentDefinition(
                id = "a", name = "Researcher", description = "Reads a lot",
                instructions = "cite sources", modelGroupId = "grp-1", sortOrder = 1,
            ),
        )
        return cfg
    }

    @Test
    fun `custom sub agents survive a database round trip`() {
        val restored = configWithRoster().toSnapshot(json).toProviderConfig(json)
        assertEquals(2, restored.subAgents.size)
        val custom = restored.subAgents.first { !it.isBuiltIn }
        assertEquals("Researcher", custom.name)
        assertEquals("cite sources", custom.instructions)
        assertEquals("grp-1", custom.modelGroupId)
    }

    @Test
    fun `an empty roster round trips as empty rather than becoming undefined`() {
        val restored = ProviderConfig().toSnapshot(json).toProviderConfig(json)
        assertTrue(restored.subAgents.isEmpty())
    }

    @Test
    fun `a database written before sub agents existed restores cleanly`() {
        val snapshot = ProviderConfig().toSnapshot(json)
        val withoutRow = snapshot.copy(
            meta = snapshot.meta.filterNot { it.key == "sub_agents_json" },
        )
        val restored = withoutRow.toProviderConfig(json)
        assertTrue(restored.subAgents.isEmpty())
    }

    @Test
    fun `a corrupt roster row does not take the whole config down with it`() {
        val snapshot = configWithRoster().toSnapshot(json)
        val corrupted = snapshot.copy(
            meta = snapshot.meta.map {
                if (it.key == "sub_agents_json") it.copy(value = "{not json") else it
            },
        )
        val restored = corrupted.toProviderConfig(json)
        assertTrue(restored.subAgents.isEmpty())
    }
}
