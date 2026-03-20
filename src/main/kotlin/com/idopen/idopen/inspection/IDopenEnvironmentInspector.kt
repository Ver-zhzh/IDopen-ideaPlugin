package com.idopen.idopen.inspection

import com.idopen.idopen.agent.ProviderDefinitionSupport
import com.idopen.idopen.agent.ProviderType
import com.idopen.idopen.settings.DisplayLanguage
import com.idopen.idopen.settings.IDopenSettingsState
import com.intellij.openapi.project.Project

object IDopenEnvironmentInspector {
    data class InspectionReport(
        val title: String,
        val details: String,
    )

    fun inspect(project: Project): InspectionReport {
        val settings = IDopenSettingsState.getInstance()
        val language = DisplayLanguage.fromStored(settings.displayLanguage)
        val providerType = ProviderType.fromStored(settings.providerType)
        val definition = ProviderDefinitionSupport.definition(providerType)
        val providerReady = definition.isReady(settings)
        val title = if (providerReady) {
            if (language == DisplayLanguage.ZH_CN) "已可开始对话" else "Ready to chat"
        } else {
            if (language == DisplayLanguage.ZH_CN) "需要配置 Provider" else "Provider setup required"
        }
        val providerLabel = definition.label(language)
        val endpoint = definition.endpoint(settings).ifBlank {
            if (language == DisplayLanguage.ZH_CN) "<未配置>" else "<not configured>"
        }
        val details = buildString {
            appendLine(if (language == DisplayLanguage.ZH_CN) "项目根目录：${project.basePath ?: "<未知>"}" else "Project root: ${project.basePath ?: "<unknown>"}")
            appendLine(if (language == DisplayLanguage.ZH_CN) "Provider 类型：$providerLabel" else "Provider type: $providerLabel")
            appendLine(if (language == DisplayLanguage.ZH_CN) "端点：$endpoint" else "Endpoint: $endpoint")
            appendLine(
                if (language == DisplayLanguage.ZH_CN) {
                    "默认模型：${definition.preferredModel(settings).ifBlank { "<未配置>" }}"
                } else {
                    "Default model: ${definition.preferredModel(settings).ifBlank { "<not configured>" }}"
                },
            )
            appendLine(
                if (language == DisplayLanguage.ZH_CN) {
                    "Shell：${settings.shellPath.ifBlank { "<未配置>" }}"
                } else {
                    "Shell: ${settings.shellPath.ifBlank { "<not configured>" }}"
                },
            )
            appendLine(if (language == DisplayLanguage.ZH_CN) "命令超时：${settings.commandTimeoutSeconds}s" else "Command timeout: ${settings.commandTimeoutSeconds}s")
            appendLine()
            appendLine(definition.setupHint(language))
        }
        return InspectionReport(title, details)
    }
}
