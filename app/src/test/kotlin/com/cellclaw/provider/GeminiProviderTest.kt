package com.cellclaw.provider

import org.junit.Assert.*
import org.junit.Test

class GeminiProviderTest {

    @Test
    fun `name is gemini`() {
        val provider = GeminiProvider()
        assertEquals("gemini", provider.name)
    }

    @Test
    fun `default model is gemini-2-5-flash`() {
        assertEquals("gemini-2.5-flash", GeminiProvider.DEFAULT_MODEL)
    }

    @Test
    fun `api url is correct`() {
        assertTrue(GeminiProvider.API_URL.contains("generativelanguage.googleapis.com"))
    }

    @Test
    fun `implements Provider interface`() {
        val provider: Provider = GeminiProvider()
        assertEquals("gemini", provider.name)
    }
}
