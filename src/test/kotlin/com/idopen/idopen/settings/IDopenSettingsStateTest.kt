package com.idopen.idopen.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class IDopenSettingsStateTest {
    @Test
    fun `state serialization redacts chatgpt tokens while keeping them in secure storage`() {
        val backend = InMemoryBackend()
        val previousBackend = ChatGptCredentialStore.backend
        ChatGptCredentialStore.backend = backend
        try {
            val state = IDopenSettingsState()
            state.chatGptAccessToken = "access-token"
            state.chatGptRefreshToken = "refresh-token"
            state.chatGptAccountId = "org_123"

            val persisted = state.state

            assertEquals("", persisted.chatGptAccessToken)
            assertEquals("", persisted.chatGptRefreshToken)
            assertEquals("org_123", persisted.chatGptAccountId)
            assertEquals("access-token", backend.accessToken)
            assertEquals("refresh-token", backend.refreshToken)
        } finally {
            ChatGptCredentialStore.backend = previousBackend
        }
    }

    @Test
    fun `loadState migrates legacy xml tokens into secure storage`() {
        val backend = InMemoryBackend()
        val previousBackend = ChatGptCredentialStore.backend
        ChatGptCredentialStore.backend = backend
        try {
            val incoming = IDopenSettingsState().apply {
                providerType = "CHATGPT_AUTH"
                chatGptAccessToken = "legacy-access"
                chatGptRefreshToken = "legacy-refresh"
                chatGptAccountId = "org_456"
            }

            val state = IDopenSettingsState()
            state.loadState(incoming)

            assertEquals("legacy-access", state.chatGptAccessToken)
            assertEquals("legacy-refresh", state.chatGptRefreshToken)
            assertEquals("legacy-access", backend.accessToken)
            assertEquals("legacy-refresh", backend.refreshToken)
            assertEquals("", state.state.chatGptAccessToken)
            assertEquals("", state.state.chatGptRefreshToken)
        } finally {
            ChatGptCredentialStore.backend = previousBackend
        }
    }

    private class InMemoryBackend : ChatGptCredentialStore.Backend {
        var accessToken: String = ""
        var refreshToken: String = ""

        override fun get(key: String): String {
            return when {
                key.contains("access") -> accessToken
                key.contains("refresh") -> refreshToken
                else -> ""
            }
        }

        override fun set(key: String, value: String) {
            when {
                key.contains("access") -> accessToken = value
                key.contains("refresh") -> refreshToken = value
            }
        }
    }
}
