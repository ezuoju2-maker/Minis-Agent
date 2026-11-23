package com.openminis.app.tools

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.ui.chat.ImageInputPreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionGroupResolverTest {

    private fun model(inputs: List<String>?, provider: String = "test", id: String = "m") =
        LLMModel(id = id, displayName = id, provider = provider, inputModalities = inputs)


    @Test
    fun `modality image_input is vision`() {
        assertTrue(model(listOf("text", "image_input")).hasImageInput)
    }

    @Test
    fun `modality IMAGE in any case is vision`() {
        assertTrue(model(listOf("IMAGE")).hasImageInput)
        assertTrue(model(listOf("Image_Input")).hasImageInput)
        assertTrue(model(listOf("text", "Image")).hasImageInput)
    }

    @Test
    fun `text-only and unrelated modalities are not vision`() {
        assertFalse(model(listOf("text")).hasImageInput)
        assertFalse(model(listOf("text", "audio")).hasImageInput)
        assertFalse(model(listOf("images")).hasImageInput)
    }

    @Test
    fun `an uncatalogued model falls back to the provider default table`() {
        assertTrue(model(null, provider = "OpenAI").hasImageInput)
        assertTrue(model(null, provider = "Anthropic").hasImageInput)
        assertTrue(model(null, provider = "Google Gemini").hasImageInput)
        assertFalse(model(null, provider = "DeepSeek").hasImageInput)
        assertFalse(model(null, provider = "vLLM").hasImageInput)
        assertTrue(model(emptyList(), provider = "OpenAI").hasImageInput)
    }


    private fun usableVisionMember(instanceEnabled: Boolean, entry: ModelEntry): Boolean =
        instanceEnabled && entry.model.hasImageInput

    @Test
    fun `a catalog refresh that adds vision flips the resolver without re-adding the model`() {
        val before = ModelEntry(providerInstanceId = "inst", baseModel = model(listOf("text"), id = "deepseek-v4.1-flash"))
        assertFalse("text-only at add time", usableVisionMember(true, before))

        val after = before.copy(baseModel = before.baseModel.copy(inputModalities = listOf("text", "image_input")))
        assertEquals("same entry identity", before.uuid, after.uuid)
        assertTrue("resolver must read the refreshed capability", usableVisionMember(true, after))

        val withdrawn = after.copy(baseModel = after.baseModel.copy(inputModalities = listOf("text")))
        assertFalse(usableVisionMember(true, withdrawn))
    }

    @Test
    fun `a user override wins over the catalog in both directions`() {
        val catalogText = ModelEntry(providerInstanceId = "inst", baseModel = model(listOf("text")))
        val forcedOn = catalogText.copy(overrides = ModelOverrides(inputModalities = listOf("text", "image")))
        assertTrue("user turned Image Input on for a catalog-text model", usableVisionMember(true, forcedOn))

        val catalogVision = ModelEntry(providerInstanceId = "inst", baseModel = model(listOf("text", "image")))
        val forcedOff = catalogVision.copy(overrides = ModelOverrides(inputModalities = listOf("text")))
        assertFalse("user turned Image Input off", usableVisionMember(true, forcedOff))
    }

    @Test
    fun `a disabled instance disqualifies an otherwise vision-capable member`() {
        val entry = ModelEntry(providerInstanceId = "inst", baseModel = model(listOf("text", "image")))
        assertFalse(usableVisionMember(false, entry))
    }


    @Test
    fun `attaching an image to a text model does not block the send - preflight only hints`() {
        val cls = ImageInputPreflight::class.java
        assertNull("check() must not come back", cls.declaredMethods.firstOrNull { it.name == "check" })
        assertTrue("Verdict must not come back", cls.declaredClasses.none { it.simpleName == "Verdict" })
        assertTrue(ImageInputPreflight.isLikelyImageRejection(LLMError.ProviderError("bad", httpStatus = 400), 2))
        assertFalse(ImageInputPreflight.isLikelyImageRejection(LLMError.ProviderError("bad", httpStatus = 400), 0))
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertFalse(vm.contains("blockIfImagesUnsupported"))
        assertFalse(vm.contains("ImageInputPreflight.check("))
    }

    @Test
    fun `placeholder for a text model always carries the sandbox path`() {
        val path = "/var/minis/attachments/uploads/shot.png"
        val noGroup = VisionGroupResolver.noVisionImagePlaceholder(path, visionGroupConfigured = false)
        assertTrue(noGroup.contains(path))
        assertTrue("points at the shell, the tool that IS registered", noGroup.contains("shell_execute"))
        assertFalse("must not invite a tool the gate did not register", noGroup.contains("read_image"))

        val withGroup = VisionGroupResolver.noVisionImagePlaceholder(path, visionGroupConfigured = true)
        assertTrue(withGroup.contains(path))
        assertTrue(withGroup.contains("read_image"))

        assertEquals(
            "[Image attached but this model does not support vision input]",
            VisionGroupResolver.noVisionImagePlaceholder(null, visionGroupConfigured = false),
        )
    }


    @Test
    fun `drift guard - one normalised predicate everywhere, read live`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        val fn = repo.substring(repo.indexOf("fun resolveVisionCandidates("))
        assertTrue("member filter must use hasImageInput", fn.contains("if (!inst.isEnabled || !entry.model.hasImageInput) return null"))
        assertTrue("must read the live config, not a snapshot", fn.substring(0, 400).contains("ensureConfigLoaded()"))

        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("get() = currentModel?.hasImageInput == true"))
        val codeLines = vm.lines().filter { l -> val t = l.trimStart(); !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") }
        assertFalse("the inline lowercase check must not return",
            codeLines.any { it.contains("inputModalities.map { it.lowercase() }") || it.contains("inputModalities?.map { it.lowercase() }") })

        val models = ProductionSources.read("data/model/LLMModel.kt")
        assertTrue(models.contains("lowercase().removeSuffix(\"_input\").removeSuffix(\"_output\")"))
    }
}
