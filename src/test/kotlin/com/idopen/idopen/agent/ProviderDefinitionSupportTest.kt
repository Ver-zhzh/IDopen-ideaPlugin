package com.idopen.idopen.agent

import com.idopen.idopen.settings.ChatGptAuthSupport
import com.idopen.idopen.settings.DisplayLanguage
import com.idopen.idopen.settings.IDopenSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDefinitionSupportTest {
    @Test
    fun `chatgpt definition provides managed endpoint and preferred default model`() {
        val settings = IDopenSettingsState().apply {
            providerType = ProviderType.CHATGPT_AUTH.name
            defaultModel = ""
        }

        val definition = ProviderDefinitionSupport.definition(ProviderType.CHATGPT_AUTH)

        assertEquals(ChatGptAuthSupport.CODEX_API_BASE, definition.endpoint(settings))
        assertEquals(ChatGptAuthSupport.defaultModel(), definition.preferredModel(settings))
        assertTrue(definition.supportsManagedAuth)
        assertTrue(definition.supportsQuotaLookup)
        assertEquals("ChatGPT 账号", definition.shortLabel(DisplayLanguage.ZH_CN))
    }

    @Test
    fun `openai compatible definition uses manual configuration readiness`() {
        val definition = ProviderDefinitionSupport.definition(ProviderType.OPENAI_COMPATIBLE)
        val notReady = IDopenSettingsState()
        val ready = IDopenSettingsState().apply {
            baseUrl = "https://example.com/v1"
            apiKey = "key"
            defaultModel = "gpt-4.1-mini"
        }

        assertFalse(definition.isReady(notReady))
        assertTrue(definition.isReady(ready))
        assertFalse(definition.supportsManagedAuth)
        assertFalse(definition.managesEndpoint)
        assertEquals("OpenAI-compatible", definition.label(DisplayLanguage.EN_US))
    }
}
