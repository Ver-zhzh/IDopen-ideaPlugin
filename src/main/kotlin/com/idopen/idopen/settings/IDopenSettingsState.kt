package com.idopen.idopen.settings

import com.idopen.idopen.agent.ToolCallingMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "IDopenSettings", storages = [Storage("IDopenSettings.xml")])
class IDopenSettingsState : PersistentStateComponent<IDopenSettingsState> {
    var displayLanguage: String = DisplayLanguage.default().name
    var providerType: String = "OPENAI_COMPATIBLE"
    var baseUrl: String = ""
    var apiKey: String = ""
    var defaultModel: String = ""
    var knownModels: MutableList<String> = mutableListOf()
    var headersText: String = ""
    var chatGptAccessToken: String = ""
        set(value) {
            field = value
            if (!suspendCredentialSync) {
                ChatGptCredentialStore.store(field, chatGptRefreshToken)
            }
        }
    var chatGptRefreshToken: String = ""
        set(value) {
            field = value
            if (!suspendCredentialSync) {
                ChatGptCredentialStore.store(chatGptAccessToken, field)
            }
        }
    var chatGptAccessTokenExpiresAt: Long = 0L
    var chatGptAccountId: String = ""
    var chatGptEmail: String = ""
    var shellPath: String = defaultShellPath()
    var commandTimeoutSeconds: Int = 120
    var toolCallingMode: String = ToolCallingMode.AUTO.name
    var enableToolCalling: Boolean = false
    var unlimitedUsage: Boolean = false
    var trustMode: Boolean = false
    @Transient
    private var suspendCredentialSync: Boolean = false

    override fun getState(): IDopenSettingsState {
        return IDopenSettingsState().also { copy ->
            copy.suspendCredentialSync = true
            copy.displayLanguage = displayLanguage
            copy.providerType = providerType
            copy.baseUrl = baseUrl
            copy.apiKey = apiKey
            copy.defaultModel = defaultModel
            copy.knownModels = knownModels.toMutableList()
            copy.headersText = headersText
            copy.chatGptAccessToken = ""
            copy.chatGptRefreshToken = ""
            copy.chatGptAccessTokenExpiresAt = chatGptAccessTokenExpiresAt
            copy.chatGptAccountId = chatGptAccountId
            copy.chatGptEmail = chatGptEmail
            copy.shellPath = shellPath
            copy.commandTimeoutSeconds = commandTimeoutSeconds
            copy.toolCallingMode = toolCallingMode
            copy.enableToolCalling = enableToolCalling
            copy.unlimitedUsage = unlimitedUsage
            copy.trustMode = trustMode
            copy.suspendCredentialSync = false
        }
    }

    override fun loadState(state: IDopenSettingsState) {
        val migratedAccessToken = state.chatGptAccessToken
        val migratedRefreshToken = state.chatGptRefreshToken
        val storedTokens = ChatGptCredentialStore.load()

        suspendCredentialSync = true
        try {
            displayLanguage = state.displayLanguage
            providerType = state.providerType
            baseUrl = state.baseUrl
            apiKey = state.apiKey
            defaultModel = state.defaultModel
            knownModels = state.knownModels.toMutableList()
            headersText = state.headersText
            chatGptAccessToken = ""
            chatGptRefreshToken = ""
            chatGptAccessTokenExpiresAt = state.chatGptAccessTokenExpiresAt
            chatGptAccountId = state.chatGptAccountId
            chatGptEmail = state.chatGptEmail
            shellPath = state.shellPath
            commandTimeoutSeconds = state.commandTimeoutSeconds
            toolCallingMode = state.toolCallingMode
            enableToolCalling = state.enableToolCalling
            unlimitedUsage = state.unlimitedUsage
            trustMode = state.trustMode
        } finally {
            suspendCredentialSync = false
        }

        val accessToken = migratedAccessToken.ifBlank { storedTokens.accessToken }
        val refreshToken = migratedRefreshToken.ifBlank { storedTokens.refreshToken }
        if (migratedAccessToken.isNotBlank() || migratedRefreshToken.isNotBlank()) {
            ChatGptCredentialStore.store(accessToken, refreshToken)
        }
        chatGptAccessToken = accessToken
        chatGptRefreshToken = refreshToken
    }

    companion object {
        fun getInstance(): IDopenSettingsState {
            val application = ApplicationManager.getApplication()
            return application?.getService(IDopenSettingsState::class.java) ?: IDopenSettingsState()
        }

        fun defaultShellPath(): String {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) "powershell.exe" else "/bin/bash"
        }
    }
}
