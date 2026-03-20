package com.idopen.idopen.agent

import com.idopen.idopen.settings.ChatGptAuthSupport
import com.idopen.idopen.settings.IDopenSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
            providerType = ProviderType.OPENAI_COMPATIBLE.name
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
    fun `chatgpt auth settings validation returns codex provider config`() {
        val settings = IDopenSettingsState().apply {
            providerType = ProviderType.CHATGPT_AUTH.name
            chatGptAccessToken = "access-token"
            chatGptRefreshToken = "refresh-token"
            chatGptAccessTokenExpiresAt = 12345L
            chatGptAccountId = "org_123"
            defaultModel = ChatGptAuthSupport.defaultModel()
            headersText = "X-Origin: test"
        }

        val result = ProviderConfigSupport.fromSettings(settings)

        val config = assertNotNull(result.config)
        assertEquals(ProviderType.CHATGPT_AUTH, config.type)
        assertEquals(ChatGptAuthSupport.CODEX_API_BASE, config.baseUrl)
        assertEquals("refresh-token", config.refreshToken)
        assertEquals(12345L, config.accessTokenExpiresAt)
        assertEquals("org_123", config.accountId)
        assertEquals("test", config.headers["X-Origin"])
    }

    @Test
    fun `chatgpt auth settings fall back to default model when unset`() {
        val settings = IDopenSettingsState().apply {
            providerType = ProviderType.CHATGPT_AUTH.name
            chatGptAccessToken = "access-token"
            chatGptRefreshToken = "refresh-token"
            defaultModel = ""
        }

        val result = ProviderConfigSupport.fromSettings(settings)

        assertEquals(ChatGptAuthSupport.defaultModel(), assertNotNull(result.config).model)
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
        assertEquals(false, settings.trustMode)
        assertEquals(false, settings.unlimitedUsage)
        assertEquals(ToolCallingMode.AUTO.name, settings.toolCallingMode)
        assertEquals(emptyList(), settings.knownModels)
        assertEquals("", settings.chatGptAccessToken)
        assertEquals("", settings.chatGptRefreshToken)
    }
}
