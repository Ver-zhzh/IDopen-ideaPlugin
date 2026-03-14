package com.idopen.idopen.agent

import com.idopen.idopen.settings.IDopenSettingsState

enum class ProviderResponseProtocol {
    CHAT_COMPLETIONS,
}

data class ProviderRuntimeProfile(
    val config: ProviderConfig,
    val responseProtocol: ProviderResponseProtocol = ProviderResponseProtocol.CHAT_COMPLETIONS,
    val supportsStreaming: Boolean = true,
    val supportsToolCalling: Boolean,
    val effectiveToolMode: ToolCallingMode,
    val includeTools: Boolean,
    val capabilityDetail: String? = null,
)

object ProviderRuntimeSupport {
    fun resolveProfile(
        config: ProviderConfig,
        settings: IDopenSettingsState,
        capabilityLookup: (ProviderConfig) -> ToolCapability,
    ): ProviderRuntimeProfile {
        val mode = ToolCallingMode.fromStored(settings.toolCallingMode)
        return when (mode) {
            ToolCallingMode.DISABLED -> ProviderRuntimeProfile(
                config = config,
                supportsToolCalling = false,
                effectiveToolMode = ToolCallingMode.DISABLED,
                includeTools = false,
                capabilityDetail = "Tool calling disabled in settings.",
            )

            ToolCallingMode.ENABLED -> ProviderRuntimeProfile(
                config = config,
                supportsToolCalling = true,
                effectiveToolMode = ToolCallingMode.ENABLED,
                includeTools = true,
                capabilityDetail = "Tool calling forced on by settings.",
            )

            ToolCallingMode.AUTO -> {
                val capability = capabilityLookup(config)
                ProviderRuntimeProfile(
                    config = config,
                    supportsToolCalling = capability.supportsToolCalling,
                    effectiveToolMode = if (capability.supportsToolCalling) ToolCallingMode.ENABLED else ToolCallingMode.DISABLED,
                    includeTools = capability.supportsToolCalling,
                    capabilityDetail = capability.detail,
                )
            }
        }
    }
}
