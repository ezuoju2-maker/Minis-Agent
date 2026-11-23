package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAbsenceGraceTest {

    private val grace = ProviderRepository.MODEL_ABSENCE_GRACE_MS
    private val t0 = 1_800_000_000_000L

    private fun entry(
        modelId: String,
        contextWindow: Int? = null,
        isCustom: Boolean = false,
        absentSince: Long? = null,
        baseContext: Int = 1_048_576,
    ) = ModelEntry(
        providerInstanceId = "inst",
        baseModel = LLMModel(
            id = modelId,
            displayName = modelId,
            provider = "openAI",
            contextWindow = baseContext,
        ),
        overrides = ModelOverrides(contextWindow = contextWindow),
        isCustom = isCustom,
        uuid = modelId,
        absentSince = absentSince,
    )

    private fun refresh(
        existing: List<ModelEntry>,
        returned: List<String>,
        nowMs: Long,
        newBase: Int = 1_048_576,
    ): List<ModelEntry> {
        val returnedSet = returned.toSet()
        val out = mutableListOf<ModelEntry>()
        for (id in returned) {
            val prior = existing.firstOrNull { it.baseModel.id == id }
            out.add(
                entry(id, contextWindow = prior?.overrides?.contextWindow, baseContext = newBase)
                    .copy(absentSince = null),
            )
        }
        for (e in existing.filter { !it.isCustom && it.baseModel.id !in returnedSet }) {
            val since = e.absentSince ?: nowMs
            if (nowMs - since > grace) continue
            out.add(e.copy(absentSince = since))
        }
        out.addAll(existing.filter { it.isCustom && it.baseModel.id !in returnedSet })
        return out
    }

    private fun refreshOld(existing: List<ModelEntry>, returned: List<String>): List<ModelEntry> {
        val returnedSet = returned.toSet()
        val out = mutableListOf<ModelEntry>()
        for (id in returned) {
            val prior = existing.firstOrNull { it.baseModel.id == id }
            out.add(entry(id, contextWindow = prior?.overrides?.contextWindow))
        }
        out.addAll(existing.filter { it.isCustom && it.baseModel.id !in returnedSet })
        return out
    }

    private fun List<ModelEntry>.find(id: String) = firstOrNull { it.baseModel.id == id }

    private val target = "gemini-3.8-flash-high"
    private val before = listOf(
        entry(target, contextWindow = 400_000),
        entry("gemini-3.8-flash"),
        entry("gpt-4o"),
    )

    @Test
    fun `a model skipped for one refresh survives with its override`() {
        val after = refresh(before, listOf("gemini-3.8-flash", "gpt-4o"), t0)
        assertNotNull("entry must survive the refresh", after.find(target))
        assertTrue("marked unavailable", after.find(target)!!.isUnavailableFromProvider)
        assertEquals(
            "override intact",
            400_000,
            after.find(target)!!.overrides.contextWindow,
        )
    }

    @Test
    fun `the OLD rule loses both, so the test cannot pass vacuously`() {
        val old = refreshOld(before, listOf("gemini-3.8-flash", "gpt-4o"))
        assertNull("OLD: entry is gone", old.find(target))
    }

    @Test
    fun `listing it again fully restores it`() {
        val absent = refresh(before, listOf("gemini-3.8-flash", "gpt-4o"), t0)
        val back = refresh(absent, listOf(target, "gemini-3.8-flash", "gpt-4o"), t0 + 3_600_000)
        assertNull("absence mark cleared", back.find(target)!!.absentSince)
        assertFalse("no longer unavailable", back.find(target)!!.isUnavailableFromProvider)
        assertEquals("override still 400000", 400_000, back.find(target)!!.overrides.contextWindow)
    }

    @Test
    fun `base is refreshed for listed models but the override still wins`() {
        val bumped = refresh(before, listOf(target, "gpt-4o"), t0, newBase = 2_000_000)
        assertEquals(
            "base picked up the vendor's new value",
            2_000_000,
            bumped.find(target)!!.baseModel.contextWindow,
        )
        assertEquals(
            "the user's override still wins",
            400_000,
            bumped.find(target)!!.overrides.contextWindow,
        )
        assertNull("un-overridden model has no override", bumped.find("gpt-4o")!!.overrides.contextWindow)
        assertEquals(
            "…and follows the vendor's base",
            2_000_000,
            bumped.find("gpt-4o")!!.baseModel.contextWindow,
        )
    }

    @Test
    fun `repeated absences keep the first timestamp`() {
        var rolling = before
        for (i in 0 until 5) {
            rolling = refresh(rolling, listOf("gemini-3.8-flash", "gpt-4o"), t0 + i * 6L * 3_600_000)
        }
        assertEquals("absentSince is still the first miss", t0, rolling.find(target)!!.absentSince)
        assertNotNull("still present after 5 consecutive misses", rolling.find(target))
        assertEquals("override untouched", 400_000, rolling.find(target)!!.overrides.contextWindow)
    }

    @Test
    fun `absence beyond the grace window finally deletes the entry`() {
        val stamped = refresh(before, listOf("gemini-3.8-flash", "gpt-4o"), t0)
        val inside = refresh(stamped, listOf("gemini-3.8-flash", "gpt-4o"), t0 + grace - 60_000)
        assertNotNull("kept 1 minute before the deadline", inside.find(target))
        val past = refresh(stamped, listOf("gemini-3.8-flash", "gpt-4o"), t0 + grace + 60_000)
        assertNull("dropped 1 minute after the deadline", past.find(target))
    }

    @Test
    fun `the grace window spans many automatic refreshes`() {
        assertTrue("7d grace >= 20 refresh windows", grace / (6L * 3_600_000) >= 20)
    }

    @Test
    fun `a full response changes nothing`() {
        val normal = refresh(before, listOf(target, "gemini-3.8-flash", "gpt-4o"), t0)
        assertEquals("every entry kept", 3, normal.size)
        assertTrue("none marked absent", normal.none { it.isUnavailableFromProvider })
    }

    @Test
    fun `custom entries are still kept unconditionally and never marked absent`() {
        val withCustom = listOf(entry("my-custom", contextWindow = 123, isCustom = true), entry("gpt-4o"))
        val kept = refresh(withCustom, listOf("gpt-4o"), t0)
        assertNotNull("custom entry kept", kept.find("my-custom"))
        assertFalse("custom entry not marked absent", kept.find("my-custom")!!.isUnavailableFromProvider)
    }

    @Test
    fun `absence is not user intent and must not make an entry look user-modified`() {
        val absent = entry("m", absentSince = t0)
        assertFalse("absence alone is not user modification", absent.isUserModified)
        assertTrue("but it is reported as unavailable", absent.isUnavailableFromProvider)
    }
}
