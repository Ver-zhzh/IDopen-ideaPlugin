package com.idopen.idopen.settings

import com.idopen.idopen.agent.ToolCallingMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

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
    var chatGptRefreshToken: String = ""
    var chatGptAccessTokenExpiresAt: Long = 0L
    var chatGptAccountId: String = ""
    var chatGptEmail: String = ""
    var shellPath: String = defaultShellPath()
    var commandTimeoutSeconds: Int = 120
    var toolCallingMode: String = ToolCallingMode.AUTO.name
    var enableToolCalling: Boolean = false
    var unlimitedUsage: Boolean = false
    var trustMode: Boolean = false

    override fun getState(): IDopenSettingsState = this

    override fun loadState(state: IDopenSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
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
