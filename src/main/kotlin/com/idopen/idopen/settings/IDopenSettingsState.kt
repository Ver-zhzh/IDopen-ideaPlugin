package com.idopen.idopen.settings

import com.idopen.idopen.agent.ToolCallingMode
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "IDopenSettings", storages = [Storage("IDopenSettings.xml")])
class IDopenSettingsState : PersistentStateComponent<IDopenSettingsState> {
    var providerType: String = "OPENAI_COMPATIBLE"
    var baseUrl: String = ""
    var apiKey: String = ""
    var defaultModel: String = ""
    var knownModels: MutableList<String> = mutableListOf()
    var headersText: String = ""
    var shellPath: String = defaultShellPath()
    var commandTimeoutSeconds: Int = 120
    var toolCallingMode: String = ToolCallingMode.AUTO.name
    var enableToolCalling: Boolean = false

    override fun getState(): IDopenSettingsState = this

    override fun loadState(state: IDopenSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): IDopenSettingsState = service()

        fun defaultShellPath(): String {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) "powershell.exe" else "/bin/bash"
        }
    }
}
