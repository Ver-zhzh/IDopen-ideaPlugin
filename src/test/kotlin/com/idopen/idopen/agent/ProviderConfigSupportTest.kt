package com.idopen.idopen.agent

import com.idopen.idopen.settings.IDopenSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ProviderConfigSupportTest {
    @Test
    fun `parse headers skips invalid rows`() {
        val headers = ProviderConfigSupport.parseHeaders(
            """
            Authorization: Bearer token
            X-Test: hello
            invalid
            Empty:
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                "Authorization" to "Bearer token",
                "X-Test" to "hello",
            ),
            headers,
        )
    }

    @Test
    fun `settings validation returns provider config`() {
        val settings = IDopenSettingsState().apply {
            baseUrl = "http://localhost:8000/v1/"
            apiKey = "nim-key"
            defaultModel = "meta/llama-3.1-70b-instruct"
            headersText = "X-Test: 1"
        }

        val result = ProviderConfigSupport.fromSettings(settings)

        val config = assertNotNull(result.config)
        assertEquals("http://localhost:8000/v1", config.baseUrl)
        assertEquals("1", config.headers["X-Test"])
    }

    @Test
    fun `validation can skip model requirement for model discovery`() {
        val result = ProviderConfigSupport.fromInputs(
            baseUrl = "https://integrate.api.nvidia.com/v1",
            apiKey = "nim-key",
            model = "",
            headersText = "",
            requireModel = false,
        )

        assertNull(result.error)
        assertEquals("", assertNotNull(result.config).model)
    }

    @Test
    fun `settings default keeps tool calling disabled`() {
        val settings = IDopenSettingsState()

        assertEquals(false, settings.enableToolCalling)
        assertEquals(emptyList(), settings.knownModels)
    }
}
