package com.idopen.idopen.agent

import com.idopen.idopen.settings.IDopenSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderRuntimeSupportTest {
    @Test
    fun `auto mode disables tools when capability probe fails`() {
        val profile = ProviderRuntimeSupport.resolveProfile(
            config = ProviderConfig(
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://example.com/v1",
                apiKey = "key",
                model = "qwen",
                headers = emptyMap(),
            ),
            settings = IDopenSettingsState().apply {
                toolCallingMode = ToolCallingMode.AUTO.name
            },
            capabilityLookup = {
                ToolCapability(
                    supportsToolCalling = false,
                    detail = "unsupported",
                )
            },
        )

        assertFalse(profile.includeTools)
        assertEquals(ToolCallingMode.DISABLED, profile.effectiveToolMode)
        assertEquals("unsupported", profile.capabilityDetail)
    }

    @Test
    fun `enabled mode forces tools on without probing`() {
        var probes = 0
        val profile = ProviderRuntimeSupport.resolveProfile(
            config = ProviderConfig(
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://example.com/v1",
                apiKey = "key",
                model = "qwen",
                headers = emptyMap(),
            ),
            settings = IDopenSettingsState().apply {
                toolCallingMode = ToolCallingMode.ENABLED.name
            },
            capabilityLookup = {
                probes += 1
                ToolCapability(false)
            },
        )

        assertTrue(profile.includeTools)
        assertEquals(0, probes)
    }

    @Test
    fun `chatgpt auth auto mode enables tools without probing`() {
        var probes = 0
        val profile = ProviderRuntimeSupport.resolveProfile(
            config = ProviderConfig(
                type = ProviderType.CHATGPT_AUTH,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                apiKey = "access-token",
                model = "gpt-5.4",
                headers = emptyMap(),
                refreshToken = "refresh-token",
                accessTokenExpiresAt = Long.MAX_VALUE,
            ),
            settings = IDopenSettingsState().apply {
                toolCallingMode = ToolCallingMode.AUTO.name
            },
            capabilityLookup = {
                probes += 1
                ToolCapability(false)
            },
        )

        assertTrue(profile.includeTools)
        assertEquals(ToolCallingMode.ENABLED, profile.effectiveToolMode)
        assertEquals(ProviderResponseProtocol.RESPONSES, profile.responseProtocol)
        assertEquals(0, probes)
    }
}
