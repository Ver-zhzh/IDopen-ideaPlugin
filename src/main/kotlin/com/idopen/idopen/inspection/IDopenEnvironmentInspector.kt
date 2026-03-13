package com.idopen.idopen.inspection

import com.idopen.idopen.settings.IDopenSettingsState
import com.intellij.openapi.project.Project

object IDopenEnvironmentInspector {
    data class InspectionReport(
        val title: String,
        val details: String,
    )

    fun inspect(project: Project): InspectionReport {
        val settings = IDopenSettingsState.getInstance()
        val providerReady = settings.baseUrl.isNotBlank() &&
            settings.apiKey.isNotBlank() &&
            settings.defaultModel.isNotBlank()

        val title = if (providerReady) "Ready to chat" else "Provider setup required"
        val details = buildString {
            appendLine("Project root: ${project.basePath ?: "<unknown>"}")
            appendLine("Provider type: OpenAI-compatible")
            appendLine("Base URL: ${settings.baseUrl.ifBlank { "<not configured>" }}")
            appendLine("Default model: ${settings.defaultModel.ifBlank { "<not configured>" }}")
            appendLine("Shell: ${settings.shellPath.ifBlank { "<not configured>" }}")
            appendLine("Command timeout: ${settings.commandTimeoutSeconds}s")
            appendLine()
            appendLine("NVIDIA NIM is supported through the OpenAI-compatible chat completions interface.")
            appendLine("Use Settings > IDopen to configure base URL, API key, and model before starting a session.")
        }
        return InspectionReport(title, details)
    }
}
