package com.idopen.idopen.agent

import com.idopen.idopen.settings.ChatGptAuthSupport
import com.idopen.idopen.settings.DisplayLanguage
import com.idopen.idopen.settings.IDopenSettingsState

data class ProviderDefinition(
    val type: ProviderType,
    val responseProtocol: ProviderResponseProtocol,
    val supportsManagedAuth: Boolean,
    val supportsQuotaLookup: Boolean,
    val managesEndpoint: Boolean,
    val managesCredentials: Boolean,
    private val zhLabel: String,
    private val enLabel: String,
    private val zhShortLabel: String = zhLabel,
    private val enShortLabel: String = enLabel,
    private val zhSetupHint: String,
    private val enSetupHint: String,
    private val zhBaseUrlHint: String,
    private val enBaseUrlHint: String,
    private val zhCredentialHint: String,
    private val enCredentialHint: String,
    private val endpointResolver: (IDopenSettingsState) -> String,
    private val modelOptionsResolver: (IDopenSettingsState) -> List<String>,
    private val preferredModelResolver: (IDopenSettingsState) -> String,
    private val readyResolver: (IDopenSettingsState) -> Boolean,
) {
    fun label(language: DisplayLanguage): String = if (language == DisplayLanguage.ZH_CN) zhLabel else enLabel

    fun shortLabel(language: DisplayLanguage): String = if (language == DisplayLanguage.ZH_CN) zhShortLabel else enShortLabel

    fun setupHint(language: DisplayLanguage): String = if (language == DisplayLanguage.ZH_CN) zhSetupHint else enSetupHint

    fun baseUrlHint(language: DisplayLanguage): String = if (language == DisplayLanguage.ZH_CN) zhBaseUrlHint else enBaseUrlHint

    fun credentialHint(language: DisplayLanguage): String = if (language == DisplayLanguage.ZH_CN) zhCredentialHint else enCredentialHint

    fun endpoint(settings: IDopenSettingsState): String = endpointResolver(settings)

    fun modelOptions(settings: IDopenSettingsState): List<String> = modelOptionsResolver(settings)

    fun preferredModel(settings: IDopenSettingsState): String = preferredModelResolver(settings)

    fun isReady(settings: IDopenSettingsState): Boolean = readyResolver(settings)
}

object ProviderDefinitionSupport {
    private val openAiCompatible = ProviderDefinition(
        type = ProviderType.OPENAI_COMPATIBLE,
        responseProtocol = ProviderResponseProtocol.CHAT_COMPLETIONS,
        supportsManagedAuth = false,
        supportsQuotaLookup = false,
        managesEndpoint = false,
        managesCredentials = false,
        zhLabel = "OpenAI 兼容接口",
        enLabel = "OpenAI-compatible",
        zhSetupHint = "请在设置 > IDopen 中配置 Base URL、API 密钥和默认模型。",
        enSetupHint = "Use Settings > IDopen to configure the base URL, API key, and default model.",
        zhBaseUrlHint = "OpenAI-compatible 提供商的 Base URL。",
        enBaseUrlHint = "Base URL for the OpenAI-compatible provider.",
        zhCredentialHint = "OpenAI-compatible 提供商使用的 API 密钥。",
        enCredentialHint = "API key used for the OpenAI-compatible provider.",
        endpointResolver = { settings -> settings.baseUrl.trim() },
        modelOptionsResolver = { settings -> settings.knownModels.toList() },
        preferredModelResolver = { settings -> settings.defaultModel.trim() },
        readyResolver = { settings ->
            settings.baseUrl.isNotBlank() &&
                settings.apiKey.isNotBlank() &&
                settings.defaultModel.isNotBlank()
        },
    )

    private val chatGptAuth = ProviderDefinition(
        type = ProviderType.CHATGPT_AUTH,
        responseProtocol = ProviderResponseProtocol.RESPONSES,
        supportsManagedAuth = true,
        supportsQuotaLookup = true,
        managesEndpoint = true,
        managesCredentials = true,
        zhLabel = "ChatGPT 账号（GPT 授权）",
        enLabel = "ChatGPT account (GPT auth)",
        zhShortLabel = "ChatGPT 账号",
        enShortLabel = "ChatGPT account",
        zhSetupHint = "请在设置 > IDopen 中登录 ChatGPT 账号并选择 GPT 模型。",
        enSetupHint = "Use Settings > IDopen to sign in with your ChatGPT account and choose a GPT model.",
        zhBaseUrlHint = "ChatGPT 登录模式下自动管理，无需手动填写。",
        enBaseUrlHint = "Managed automatically for ChatGPT login.",
        zhCredentialHint = "ChatGPT 登录会单独保存 OAuth 令牌。",
        enCredentialHint = "ChatGPT login stores OAuth tokens separately.",
        endpointResolver = { ChatGptAuthSupport.CODEX_API_BASE },
        modelOptionsResolver = { ChatGptAuthSupport.supportedModels() },
        preferredModelResolver = { settings ->
            val supportedModels = ChatGptAuthSupport.supportedModels()
            settings.defaultModel.trim().takeIf { it in supportedModels } ?: ChatGptAuthSupport.defaultModel()
        },
        readyResolver = { settings ->
            ChatGptAuthSupport.getStatus(settings).loggedIn &&
                ChatGptAuthSupport.supportedModels().isNotEmpty()
        },
    )

    fun definition(type: ProviderType): ProviderDefinition {
        return when (type) {
            ProviderType.OPENAI_COMPATIBLE -> openAiCompatible
            ProviderType.CHATGPT_AUTH -> chatGptAuth
        }
    }
}
