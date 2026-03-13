package com.idopen.idopen.settings

import com.idopen.idopen.agent.OpenAICompatibleClient
import com.idopen.idopen.agent.ProviderConfig
import com.idopen.idopen.agent.ProviderConfigSupport
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class IDopenSettingsConfigurable : Configurable {
    private val settings = IDopenSettingsState.getInstance()
    private val client = OpenAICompatibleClient()

    private val providerTypeField = JBTextField("OpenAI-compatible")
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelOptions = mutableListOf<String>()
    private val modelField = JComboBox<String>(CollectionComboBoxModel(modelOptions))
    private val shellPathField = TextFieldWithBrowseButton()
    private val timeoutField = JSpinner(SpinnerNumberModel(120, 5, 3600, 5))
    private val enableToolsCheckBox = JBCheckBox("启用工具调用（部分模型不支持；若聊天报 404，请关闭）")
    private val headersArea = JBTextArea()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "IDopen"

    override fun createComponent(): JComponent {
        if (panel != null) return panel!!

        providerTypeField.isEditable = false
        providerTypeField.toolTipText = "当前版本只支持 OpenAI-compatible chat completions。"

        modelField.isEditable = true
        modelField.preferredSize = Dimension(260, 32)

        shellPathField.addBrowseFolderListener(
            "选择 Shell",
            "选择 run_command 使用的 Shell 可执行文件。",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )

        headersArea.lineWrap = true
        headersArea.wrapStyleWord = true
        headersArea.rows = 5
        headersArea.toolTipText = "可选自定义请求头，每行一个：Header-Name: value"

        val headerPanel = JPanel(BorderLayout(0, 6))
        headerPanel.add(JBLabel("请求头（可选，每行一个：Header-Name: value）"), BorderLayout.NORTH)
        headerPanel.add(JBScrollPane(headersArea), BorderLayout.CENTER)
        headerPanel.preferredSize = Dimension(420, 140)

        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        val nimPresetButton = JButton("应用 NVIDIA NIM 预设")
        val testConnectionButton = JButton("测试连接")
        val fetchModelsButton = JButton("获取模型")
        val clearHeadersButton = JButton("清空请求头")
        actionPanel.add(nimPresetButton)
        actionPanel.add(testConnectionButton)
        actionPanel.add(fetchModelsButton)
        actionPanel.add(clearHeadersButton)

        nimPresetButton.addActionListener {
            if (baseUrlField.text.isBlank()) {
                baseUrlField.text = "https://integrate.api.nvidia.com/v1"
            }
            if (selectedModel().isBlank()) {
                setSelectedModel("meta/llama-3.1-70b-instruct")
            }
            providerTypeField.text = "OpenAI-compatible"
            if (shellPathField.text.isBlank()) {
                shellPathField.text = IDopenSettingsState.defaultShellPath()
            }
            enableToolsCheckBox.isSelected = false
            Messages.showInfoMessage(
                panel,
                "已应用 NVIDIA NIM 预设。默认关闭工具调用，兼容性更稳；确认模型支持后再开启。",
                "IDopen",
            )
        }

        testConnectionButton.addActionListener {
            val config = currentProviderConfig(requireModel = false) ?: return@addActionListener
            runProviderAction("测试连接") {
                client.testConnection(config)
            }.onSuccess { result ->
                if (result.models.isNotEmpty()) {
                    replaceModelOptions(result.models)
                }
                val sampleModels = result.models.take(5)
                val modelHint = if (sampleModels.isEmpty()) {
                    ""
                } else {
                    "\n示例模型：${sampleModels.joinToString("、")}"
                }
                Messages.showInfoMessage(panel, result.message + modelHint, "连接测试")
            }.onFailure { error ->
                Messages.showErrorDialog(panel, error.message ?: "连接失败。", "连接测试")
            }
        }

        fetchModelsButton.addActionListener {
            val config = currentProviderConfig(requireModel = false) ?: return@addActionListener
            runProviderAction("获取模型") {
                client.listModels(config)
            }.onSuccess { models ->
                if (models.isEmpty()) {
                    Messages.showWarningDialog("接口已连通，但没有返回可选模型。", "获取模型")
                    return@onSuccess
                }
                replaceModelOptions(models)
                val selected = selectedModel().takeIf { it in models } ?: models.first()
                setSelectedModel(selected)
                Messages.showInfoMessage(
                    panel,
                    "已获取 ${models.size} 个模型，并填入下拉列表。\n当前选中：$selected",
                    "获取模型",
                )
            }.onFailure { error ->
                Messages.showErrorDialog(panel, error.message ?: "获取模型失败。", "获取模型")
            }
        }

        clearHeadersButton.addActionListener {
            headersArea.text = ""
        }

        enableToolsCheckBox.toolTipText =
            "开启后会向模型发送 tools/tool_choice。部分 NVIDIA NIM 模型不支持 function calling，出现 404 时请关闭。"

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider：", providerTypeField)
            .addLabeledComponent("接口地址：", baseUrlField)
            .addLabeledComponent("API Key：", apiKeyField)
            .addLabeledComponent("默认模型：", modelField)
            .addLabeledComponent("Shell 可执行文件：", shellPathField)
            .addLabeledComponent("命令超时（秒）：", timeoutField)
            .addComponent(enableToolsCheckBox)
            .addComponent(actionPanel)
            .addComponent(headerPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        return baseUrlField.text.trim() != settings.baseUrl ||
            String(apiKeyField.password) != settings.apiKey ||
            selectedModel() != settings.defaultModel ||
            shellPathField.text.trim() != settings.shellPath ||
            (timeoutField.value as Int) != settings.commandTimeoutSeconds ||
            headersArea.text.trim() != settings.headersText.trim() ||
            enableToolsCheckBox.isSelected != settings.enableToolCalling ||
            modelOptions != settings.knownModels
    }

    override fun apply() {
        settings.providerType = "OPENAI_COMPATIBLE"
        settings.baseUrl = baseUrlField.text.trim()
        settings.apiKey = String(apiKeyField.password)
        settings.defaultModel = selectedModel()
        settings.knownModels = modelOptions.toMutableList()
        settings.shellPath = shellPathField.text.trim().ifBlank { IDopenSettingsState.defaultShellPath() }
        settings.commandTimeoutSeconds = timeoutField.value as Int
        settings.headersText = headersArea.text.trim()
        settings.enableToolCalling = enableToolsCheckBox.isSelected
    }

    override fun reset() {
        providerTypeField.text = "OpenAI-compatible"
        baseUrlField.text = settings.baseUrl
        apiKeyField.text = settings.apiKey
        replaceModelOptions(settings.knownModels)
        setSelectedModel(settings.defaultModel)
        shellPathField.text = settings.shellPath.ifBlank { IDopenSettingsState.defaultShellPath() }
        timeoutField.value = settings.commandTimeoutSeconds
        headersArea.text = settings.headersText
        enableToolsCheckBox.isSelected = settings.enableToolCalling
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun currentProviderConfig(requireModel: Boolean): ProviderConfig? {
        val validation = ProviderConfigSupport.fromInputs(
            baseUrl = baseUrlField.text,
            apiKey = String(apiKeyField.password),
            model = selectedModel(),
            headersText = headersArea.text,
            requireModel = requireModel,
        )
        if (validation.error != null) {
            Messages.showErrorDialog(panel, validation.error, "配置不完整")
        }
        return validation.config
    }

    private fun selectedModel(): String {
        val editorItem = modelField.editor.item?.toString()?.trim().orEmpty()
        return editorItem.ifBlank { modelField.selectedItem?.toString()?.trim().orEmpty() }
    }

    private fun setSelectedModel(model: String) {
        if (model.isBlank()) {
            modelField.editor.item = ""
            return
        }
        if (model !in modelOptions) {
            modelOptions += model
            syncModelList()
        }
        modelField.selectedItem = model
        modelField.editor.item = model
    }

    private fun replaceModelOptions(models: List<String>) {
        modelOptions.clear()
        modelOptions.addAll(models.distinct())
        syncModelList()
    }

    private fun syncModelList() {
        modelField.model = CollectionComboBoxModel(modelOptions.toMutableList(), selectedModel().takeIf { it.isNotBlank() })
        modelField.isEditable = true
    }

    private fun <T> runProviderAction(title: String, action: () -> T): Result<T> {
        var result: Result<T>? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { result = runCatching(action) },
            title,
            true,
            null,
        )
        return result ?: Result.failure(IllegalStateException("$title 未执行。"))
    }
}
