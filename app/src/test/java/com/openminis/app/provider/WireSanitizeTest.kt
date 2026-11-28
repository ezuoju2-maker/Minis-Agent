package com.openminis.app.provider

import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireSanitizeTest {

    @Test
    fun trimsWhitespaceAndNewlinesFromTokens() {
        assertEquals("sk-abc", WireSanitize.token("  sk-abc \n"))
        assertEquals("sk-abc", WireSanitize.token("sk-abc\r\n"))
        assertEquals("", WireSanitize.token("   "))
        assertEquals("", WireSanitize.token(null))
    }

    @Test
    fun stripsEmbeddedNewlinesFromTokens() {
        assertEquals("sk-abc", WireSanitize.token("sk-abc\n"))
        assertEquals("BearerLike", WireSanitize.token("BearerLike\r\n"))
    }

    @Test
    fun normalisesBaseUrls() {
        assertEquals("https://api.example.com/v1", WireSanitize.baseUrl("  https://api.example.com/v1/  "))
        assertEquals("https://api.example.com", WireSanitize.baseUrl("api.example.com"))
        assertEquals("https://api.example.com", WireSanitize.baseUrl("https://api.example.com///"))
        assertNull(WireSanitize.baseUrl("   "))
        assertNull(WireSanitize.baseUrl(null))
        assertEquals("http://127.0.0.1:8080/v1", WireSanitize.baseUrl("http://127.0.0.1:8080/v1"))
    }

    @Test
    fun queryEncodingEscapesPastedKeys() {
        assertEquals("sk%2Dabc%2B", WireSanitize.queryEncoded("sk-abc+"))
        assertEquals("abc", WireSanitize.queryEncoded(" abc "))
    }

    private fun instance(url: String?) = ProviderInstance(
        id = "p1",
        label = "test",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = url,
        appendV1Suffix = true,
    )

    @Test
    fun effectiveBaseUrlToleratesPastedWhitespace() {
        assertEquals("https://api.example.com/v1", instance(" https://api.example.com/ ").effectiveBaseURL)
        assertEquals("https://api.example.com/v1", instance("api.example.com").effectiveBaseURL)
        assertNull(instance("  ").effectiveBaseURL)
    }

    @Test
    fun effectiveBaseUrlKeepsExplicitV1() {
        assertEquals("https://api.example.com/v1", instance("https://api.example.com/v1").effectiveBaseURL)
        assertEquals("https://api.example.com/v1", instance("https://api.example.com/v1/").effectiveBaseURL)
    }
}
