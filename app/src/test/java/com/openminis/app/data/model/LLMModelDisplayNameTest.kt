package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LLMModelDisplayNameTest {

    @Test fun `brand names are rewritten to their canonical casing`() {
        assertEquals("DeepSeek V4 Pro", LLMModel.modelDisplayName("deepseek-v4-pro"))
        assertEquals("DeepSeek Flash", LLMModel.modelDisplayName("deepseek-flash"))
    }

    @Test fun `known uppercase acronyms are preserved`() {
        assertEquals("GPT 5.6 Sol", LLMModel.modelDisplayName("gpt-5.6-sol"))
    }

    @Test fun `plain hyphenated ids are title-cased`() {
        assertEquals("Gemini 3.5 Flash Lite", LLMModel.modelDisplayName("gemini-3.5-flash-lite"))
    }

    @Test fun `slash-namespaced ids are formatted per segment`() {
        assertEquals(
            "Kimi Kimi K2.6",
            LLMModel.modelDisplayName("kimi/kimi-k2.6"),
        )
    }

    @Test fun `xxl is uppercased like the other acronyms`() {
        assertEquals("Flan T5 XXL", LLMModel.modelDisplayName("flan-t5-xxl"))
    }

    @Test fun `repeated and mixed separators collapse cleanly`() {
        assertEquals("Org Model V2", LLMModel.modelDisplayName("org//model--v2"))
    }

    @Test fun `blank id returns blank`() {
        assertEquals("", LLMModel.modelDisplayName(""))
    }
}
