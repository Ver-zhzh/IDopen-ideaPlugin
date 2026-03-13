package com.idopen.idopen.agent

import com.idopen.idopen.settings.IDopenSettingsState

object ProviderConfigSupport {
    data class ValidationResult(
        val config: ProviderConfig?,
        val error: String?,
    )

    fun fromSettings(settings: IDopenSettingsState = IDopenSettingsState.getInstance()): ValidationResult {
        return fromInputs(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.defaultModel,
            headersText = settings.headersText,
        )
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

        if (normalizedBaseUrl.isBlank()) return ValidationResult(null, "请填写接口地址。")
        if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
            return ValidationResult(null, "接口地址必须以 http:// 或 https:// 开头。")
        }
        if (normalizedApiKey.isBlank()) return ValidationResult(null, "请填写 API Key。")
        if (requireModel && normalizedModel.isBlank()) return ValidationResult(null, "请填写默认模型。")

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
