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
}
