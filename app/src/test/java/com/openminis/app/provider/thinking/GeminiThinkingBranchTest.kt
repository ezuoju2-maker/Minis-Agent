package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiThinkingBranchTest {

    private fun config(id: String, level: ThinkingLevel) =
        ThinkingRuleResolver.geminiThinkingConfig(id, level)

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
    )


    @Test
    fun `the 3x branch speaks thinkingLevel and the 2_5 branch speaks thinkingBudget`() {
        for (level in levels) {
            val three = config("gemini-3-pro-preview", level)!!
            assertTrue("3.x must use thinkingLevel at $level: $three", three.has("thinkingLevel"))
            assertFalse("3.x must never use thinkingBudget at $level: $three", three.has("thinkingBudget"))

            for (id in listOf("gemini-2.5-pro", "gemini-2.5-flash")) {
                val two = config(id, level)!!
                assertTrue("$id must use thinkingBudget at $level: $two", two.has("thinkingBudget"))
                assertFalse("$id must never use thinkingLevel at $level: $two", two.has("thinkingLevel"))
            }
        }
    }

    @Test
    fun `2_5-pro floors at 128 while 2_5-flash floors at 0`() {
        assertEquals(128, config("gemini-2.5-pro", ThinkingLevel.OFF)!!.getInt("thinkingBudget"))
        assertEquals(0, config("gemini-2.5-flash", ThinkingLevel.OFF)!!.getInt("thinkingBudget"))
        assertTrue(
            "pro's floor must stay strictly above flash's — 0 is a 400 on pro",
            config("gemini-2.5-pro", ThinkingLevel.OFF)!!.getInt("thinkingBudget") >
                config("gemini-2.5-flash", ThinkingLevel.OFF)!!.getInt("thinkingBudget"),
        )
        for (level in levels.filter { it.isEnabled }) {
            assertTrue(
                "pro must budget more than flash at $level",
                config("gemini-2.5-pro", level)!!.getInt("thinkingBudget") >
                    config("gemini-2.5-flash", level)!!.getInt("thinkingBudget"),
            )
        }
    }

    @Test
    fun `includeThoughts tracks the enabled tiers, on every branch`() {
        for (id in listOf("gemini-3-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash")) {
            assertFalse(
                "$id must not ask for thoughts at OFF: ${config(id, ThinkingLevel.OFF)}",
                config(id, ThinkingLevel.OFF)!!.optBoolean("includeThoughts", false),
            )
            for (level in levels.filter { it.isEnabled }) {
                assertTrue(
                    "$id must ask for thoughts at $level",
                    config(id, level)!!.optBoolean("includeThoughts", false),
                )
            }
        }
    }


    @Test
    fun `flash-lite is not swept in by the flash substring`() {
        assertTrue(
            "precondition: the ids really do overlap, which is why a guard is needed",
            "gemini-2.5-flash-lite".contains("gemini-2.5-flash"),
        )
        for (level in levels) {
            assertNull(
                "gemini-2.5-flash-lite must take NO thinking config at $level",
                config("gemini-2.5-flash-lite", level),
            )
        }
        assertNull(
            "the lite PREVIEW spelling is still lite",
            config("gemini-2.5-flash-lite-preview-06-17", ThinkingLevel.HIGH),
        )
    }

    @Test
    fun `no id is claimed by two of the three families`() {
        val claims = mapOf(
            "gemini-3-pro-preview" to "thinkingLevel",
            "gemini-3.1-flash" to "thinkingLevel",
            "gemini-2.5-pro" to "thinkingBudget",
            "gemini-2.5-pro-exp-0827" to "thinkingBudget",
            "gemini-2.5-flash" to "thinkingBudget",
        )
        for ((id, key) in claims) {
            val c = config(id, ThinkingLevel.MEDIUM)!!
            assertEquals("$id must emit exactly one thinking key: $c", 2, c.length())
            assertTrue("$id must emit $key: $c", c.has(key))
        }
    }

    @Test
    fun `the 3x family check precedes the 2_5 checks`() {
        val src = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        val pro = src.indexOf("val is25Pro = ")
        val flash = src.indexOf("val is25Flash = ")
        val lite = src.indexOf("val is25FlashLite = ")
        val familyBranches = src.substring(src.indexOf("null -> when {"))
        val three = familyBranches.indexOf("isGeminiLevelFamily(modelId) ->")
        assertTrue("the level-family branch not found", three > 0)
        assertTrue(
            "the level-family branch must come before the 2.5 branches",
            three < familyBranches.indexOf("is25Pro ->") && three < familyBranches.indexOf("is25Flash ->"),
        )
        assertTrue("the 2.5 predicates must still be pro then flash", pro in 1 until flash)
        assertTrue("is25FlashLite must exist as its own predicate", lite > flash)
        assertTrue(
            "the flash predicate must still carry its anti-lite guard — this is the collapse",
            src.contains("modelId.contains(\"gemini-2.5-flash\") && !modelId.contains(\"lite\")"),
        )
        assertTrue(
            "flash-lite must still short-circuit to null before the branch table",
            src.contains("if (is25FlashLite) return null"),
        )
    }


    @Test
    fun `3x Flash floors at minimal through 3_6 and at low from 3_7 up`() {
        for (id in listOf("gemini-3-flash-preview", "gemini-3.5-flash", "gemini-3.6-flash")) {
            assertEquals(
                "$id accepts minimal (verified on-device)",
                "minimal",
                config(id, ThinkingLevel.OFF)!!.getString("thinkingLevel"),
            )
        }
        for (id in listOf("gemini-3.7-flash", "gemini-3.8-flash", "gemini-3.10-flash")) {
            assertEquals(
                "$id must fall back to low — minimal is a hard 400",
                "low",
                config(id, ThinkingLevel.OFF)!!.getString("thinkingLevel"),
            )
        }
    }

    @Test
    fun `3x Pro floors at low regardless of minor version`() {
        for (id in listOf("gemini-3-pro-preview", "gemini-3.6-pro", "gemini-3.7-pro")) {
            assertEquals("low", config(id, ThinkingLevel.OFF)!!.getString("thinkingLevel"))
        }
    }

    @Test
    fun `the 3_7 floor does not touch the enabled tiers`() {
        assertEquals("low", config("gemini-3.7-flash", ThinkingLevel.LOW)!!.getString("thinkingLevel"))
        assertEquals("medium", config("gemini-3.7-flash", ThinkingLevel.MEDIUM)!!.getString("thinkingLevel"))
        for (level in listOf(ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA)) {
            assertEquals(
                "Gemini's ladder tops out at high, so $level maps there",
                "high",
                config("gemini-3.7-flash", level)!!.getString("thinkingLevel"),
            )
        }
    }


    @Test
    fun `specialized modalities take no thinking config even when they match a family`() {
        val alsoMatchesAFamily = listOf(
            "gemini-3.1-flash-tts-preview" to "gemini-3",
            "gemini-2.5-flash-preview-tts" to "gemini-2.5-flash",
            "gemini-2.5-pro-preview-tts" to "gemini-2.5-pro",
            "gemini-3-pro-image-preview" to "gemini-3",
        )
        for ((id, family) in alsoMatchesAFamily) {
            assertTrue(
                "precondition: $id must really match the $family pattern, else the row proves nothing",
                id.contains(family),
            )
            for (level in levels) {
                assertNull("$id must take no thinking config at $level", config(id, level))
            }
        }
    }

    @Test
    fun `all four specialized suffixes are recognised, trailing or mid-id`() {
        for (suffix in listOf("tts", "image", "embedding", "vision")) {
            assertNull(
                "trailing -$suffix",
                config("gemini-3-pro-$suffix", ThinkingLevel.HIGH),
            )
            assertNull(
                "mid-id -$suffix-",
                config("gemini-3-pro-$suffix-preview", ThinkingLevel.HIGH),
            )
        }
    }

    @Test
    fun `the suffix match is case-insensitive`() {
        assertNull(config("Gemini-3.1-Flash-TTS-Preview", ThinkingLevel.HIGH))
        assertNull(config("gemini-2.5-pro-EMBEDDING", ThinkingLevel.HIGH))
    }

    @Test
    fun `the suffix match does not fire on an incidental substring`() {
        assertNotNull(
            "a plain 3.x flash id is unaffected",
            config("gemini-3-flash-preview", ThinkingLevel.HIGH),
        )
        assertNotNull(
            "'vision' as part of a longer word is not the -vision modality",
            config("gemini-2.5-pro-envisioned", ThinkingLevel.HIGH),
        )
    }

    @Test
    fun `an unknown gemini id takes no config rather than a guessed default`() {
        for (id in listOf("gemini-flash-latest", "gemini-2.0-pro", "unknown-model")) {
            assertNull(config(id, ThinkingLevel.HIGH))
        }
    }


    @Test
    fun `the specialized-suffix check still runs before every family branch`() {
        val src = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        val fnAt = src.indexOf("fun geminiThinkingConfig(")
        assertTrue("geminiThinkingConfig not found", fnAt > 0)
        val body = src.substring(fnAt)
        val suffixCheck = body.indexOf("noThinkingSuffixes")
        val familyCheck = body.indexOf("isGeminiLevelFamily(modelId) ->")
        assertTrue("the suffix list is gone", suffixCheck > 0)
        assertTrue(
            "the specialized-suffix check must precede the family branches — below them it is dead code (GH#226)",
            suffixCheck < familyCheck,
        )
        assertTrue(
            "the suffix list must still name all four modalities",
            body.contains("listOf(\"-tts\", \"-image\", \"-embedding\", \"-vision\")"),
        )
        assertTrue(
            "the suffix match must stay case-insensitive via the lowercased id",
            body.contains("val lowerId = modelId.lowercase()"),
        )
        assertTrue(
            "the 3.7 floor must stay a minor-version comparison, not an id list",
            src.contains("v.major == 3 && (v.minor == null || v.minor < 7)"),
        )
        assertTrue(
            "…driven by the gemini-<major>[.-<minor>] regex (OpenMinis#470)",
            src.contains("Regex(\"\"\"gemini-(\\d+)(?:[.-](\\d+))?\"\"\")"),
        )
    }
}
