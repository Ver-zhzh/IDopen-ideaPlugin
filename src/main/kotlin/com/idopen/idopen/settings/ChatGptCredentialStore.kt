package com.idopen.idopen.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager

object ChatGptCredentialStore {
    private const val SERVICE_NAME = "IDopen.ChatGPT"
    private const val ACCESS_TOKEN_KEY = "access-token"
    private const val REFRESH_TOKEN_KEY = "refresh-token"

    internal data class Tokens(
        val accessToken: String,
        val refreshToken: String,
    )

    internal interface Backend {
        fun get(key: String): String

        fun set(key: String, value: String)
    }

    private class PasswordSafeBackend : Backend {
        override fun get(key: String): String {
            return passwordSafeOrNull()
                ?.get(attributes(key))
                ?.getPasswordAsString()
                .orEmpty()
        }

        override fun set(key: String, value: String) {
            val credential = value.takeIf { it.isNotBlank() }?.let { Credentials(key, it) }
            passwordSafeOrNull()?.set(attributes(key), credential)
        }

        private fun attributes(key: String): CredentialAttributes {
            return CredentialAttributes("$SERVICE_NAME:$key")
        }

        private fun passwordSafeOrNull(): PasswordSafe? {
            if (ApplicationManager.getApplication() == null) {
                return null
            }
            return runCatching { PasswordSafe.instance }.getOrNull()
        }
    }

    @Volatile
    internal var backend: Backend = PasswordSafeBackend()

    internal fun load(): Tokens {
        return Tokens(
            accessToken = backend.get(ACCESS_TOKEN_KEY),
            refreshToken = backend.get(REFRESH_TOKEN_KEY),
        )
    }

    fun store(accessToken: String, refreshToken: String) {
        backend.set(ACCESS_TOKEN_KEY, accessToken)
        backend.set(REFRESH_TOKEN_KEY, refreshToken)
    }

    fun clear() {
        store("", "")
    }
}
