package com.idopen.idopen.agent

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "IDopenAgentSessions", storages = [Storage("IDopenAgentSessions.xml")])
class AgentSessionStore : PersistentStateComponent<AgentSessionStore.State> {
    class State {
        var payloadJson: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun save(activeSessionId: String, sessions: List<PersistedSessionState>) {
        state.payloadJson = SessionPersistenceSupport.encode(activeSessionId, sessions)
    }

    fun restore(): RestoredSessions? {
        val payload = state.payloadJson.trim()
        if (payload.isBlank()) return null
        return runCatching { SessionPersistenceSupport.decode(payload) }.getOrNull()
    }
}
