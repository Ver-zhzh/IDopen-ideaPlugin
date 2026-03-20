package com.idopen.idopen.agent

import com.idopen.idopen.settings.ChatGptAuthSupport
import com.idopen.idopen.settings.IDopenSettingsState

object ProviderConfigSupport {
    data class ValidationResult(
        val config: ProviderConfig?,
        val error: String?,
    )

    fun fromSettings(settings: IDopenSettingsState = IDopenSettingsState.getInstance()): ValidationResult {
        val providerType = ProviderType.fromStored(settings.providerType)
        val definition = ProviderDefinitionSupport.definition(providerType)
        val preferredModel = definition.preferredModel(settings)
        return when (providerType) {
            ProviderType.OPENAI_COMPATIBLE -> fromInputs(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = preferredModel,
                headersText = settings.headersText,
            )

            ProviderType.CHATGPT_AUTH -> fromChatGptAuth(
                accessToken = settings.chatGptAccessToken,
                refreshToken = settings.chatGptRefreshToken,
                accessTokenExpiresAt = settings.chatGptAccessTokenExpiresAt,
                accountId = settings.chatGptAccountId,
                model = preferredModel,
                headersText = settings.headersText,
            )
        }
    }

    fun fromInputs(
        baseUrl: String,
        apiKey: String,
        model: String,
        headersText: String,
        requireModel: Boolean = true,
    ): ValidationResult {
        val normalizedBaseUrl = baseUrl.trim()
        val normalizedApiKey = apiKey.trim()
        val normalizedModel = model.trim()

        if (normalizedBaseUrl.isBlank()) return ValidationResult(null, "Please provide a base URL.")
        if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
            return ValidationResult(null, "Base URL must start with http:// or https://.")
        }
        if (normalizedApiKey.isBlank()) return ValidationResult(null, "Please provide an API key.")
        if (requireModel && normalizedModel.isBlank()) return ValidationResult(null, "Please provide a default model.")

        return ValidationResult(
            config = ProviderConfig(
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = normalizedBaseUrl.removeSuffix("/"),
                apiKey = normalizedApiKey,
                model = normalizedModel,
                headers = parseHeaders(headersText),
            ),
            error = null,
        )
    }

    fun fromChatGptAuth(
        accessToken: String,
        refreshToken: String,
        accessTokenExpiresAt: Long,
        accountId: String?,
        model: String,
        headersText: String,
        requireModel: Boolean = true,
    ): ValidationResult {
        val normalizedModel = model.trim()
        val normalizedAccessToken = accessToken.trim()
        val normalizedRefreshToken = refreshToken.trim()
        if (normalizedAccessToken.isBlank() && normalizedRefreshToken.isBlank()) {
            return ValidationResult(null, "Please sign in with ChatGPT first.")
        }
        if (requireModel && normalizedModel.isBlank()) {
            return ValidationResult(null, "Please select a GPT model.")
        }

        return ValidationResult(
            config = ProviderConfig(
                type = ProviderType.CHATGPT_AUTH,
                baseUrl = ChatGptAuthSupport.CODEX_API_BASE,
                apiKey = normalizedAccessToken,
                model = normalizedModel,
                headers = parseHeaders(headersText),
                refreshToken = normalizedRefreshToken.ifBlank { null },
                accessTokenExpiresAt = accessTokenExpiresAt.takeIf { it > 0L },
                accountId = accountId?.trim()?.ifBlank { null },
            ),
            error = null,
        )
    }

    fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0 || separator == line.lastIndex) return@mapNotNull null
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (key.isBlank() || value.isBlank()) return@mapNotNull null
                key to value
            }
            .toMap()
    }
}
