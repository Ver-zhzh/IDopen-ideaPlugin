package com.idopen.idopen.settings

import com.idopen.idopen.agent.OpenAICompatibleClient
import com.idopen.idopen.agent.ProviderConfig
import com.idopen.idopen.agent.ProviderConfigSupport
import com.idopen.idopen.agent.ProviderDefinitionSupport
import com.idopen.idopen.agent.ProviderType
import com.idopen.idopen.agent.ToolCallingMode
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.time.Instant
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import com.intellij.openapi.ide.CopyPasteManager

class IDopenSettingsConfigurable : Configurable {
    private val settings = IDopenSettingsState.getInstance()
    private val client = OpenAICompatibleClient()

    private val providerTypeField = JComboBox(arrayOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.CHATGPT_AUTH))
    private val displayLanguageButton = JButton()
    private val displayLanguageHintLabel = JBLabel()
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelOptions = mutableListOf<String>()
    private val modelField = JComboBox<String>(CollectionComboBoxModel(modelOptions))
    private val toolCallingModeField = JComboBox(arrayOf(ToolCallingMode.AUTO, ToolCallingMode.ENABLED, ToolCallingMode.DISABLED))
    private val shellPathField = TextFieldWithBrowseButton()
    private val timeoutField = JSpinner(SpinnerNumberModel(120, 5, 3600, 5))
    private val headersArea = JBTextArea()
    private val authStatusLabel = JBLabel()
    private val quotaStatusLabel = JBLabel()

    private val nimPresetButton = JButton()
    private val testConnectionButton = JButton()
    private val fetchModelsButton = JButton()
    private val clearHeadersButton = JButton()
    private val loginChatGptButton = JButton()
    private val loginChatGptDeviceButton = JButton()
    private val logoutChatGptButton = JButton()
    private val useChatGptModelsButton = JButton()
    private val checkChatGptQuotaButton = JButton()
    private val openChatGptUsagePageButton = JButton()

    private var panel: JPanel? = null
    private var lastProviderSelection: ProviderType? = null
    private var lastQuotaStatus: ChatGptQuotaSupport.ChatGptQuotaStatus? = null
    private var displayLanguage: DisplayLanguage = DisplayLanguage.fromStored(settings.displayLanguage)

    override fun getDisplayName(): String = "IDopen"

    override fun createComponent(): JComponent {
        if (panel != null) return panel!!

        modelField.isEditable = true
        modelField.preferredSize = Dimension(260, 32)

        shellPathField.addBrowseFolderListener(
            "选择 Shell / Choose Shell",
            "选择 run_command 使用的 shell 可执行文件。 / Choose the shell executable used by run_command.",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )

        headersArea.lineWrap = true
        headersArea.wrapStyleWord = true
        headersArea.rows = 5

        configureRenderers()
        bindActions()

        panel = JPanel(BorderLayout(0, 10))
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val openAiModelListChanged = selectedProviderType() == ProviderType.OPENAI_COMPATIBLE &&
            modelOptions != settings.knownModels
        return displayLanguage != DisplayLanguage.fromStored(settings.displayLanguage) ||
            selectedProviderType() != ProviderType.fromStored(settings.providerType) ||
            baseUrlField.text.trim() != settings.baseUrl ||
            String(apiKeyField.password) != settings.apiKey ||
            selectedModel() != settings.defaultModel ||
            selectedToolCallingMode() != ToolCallingMode.fromStored(settings.toolCallingMode) ||
            shellPathField.text.trim() != settings.shellPath ||
            (timeoutField.value as Int) != settings.commandTimeoutSeconds ||
            headersArea.text.trim() != settings.headersText.trim() ||
            openAiModelListChanged
    }

    override fun apply() {
        settings.displayLanguage = displayLanguage.name
        settings.providerType = selectedProviderType().name
        settings.baseUrl = baseUrlField.text.trim()
        settings.apiKey = String(apiKeyField.password)
        settings.defaultModel = selectedModel()
        if (selectedProviderType() == ProviderType.OPENAI_COMPATIBLE) {
            settings.knownModels = modelOptions.toMutableList()
        }
        val mode = selectedToolCallingMode()
        settings.toolCallingMode = mode.name
        settings.enableToolCalling = mode == ToolCallingMode.ENABLED
        settings.shellPath = shellPathField.text.trim().ifBlank { IDopenSettingsState.defaultShellPath() }
        settings.commandTimeoutSeconds = timeoutField.value as Int
        settings.headersText = headersArea.text.trim()
    }

    override fun reset() {
        displayLanguage = DisplayLanguage.fromStored(settings.displayLanguage)
        providerTypeField.selectedItem = ProviderType.fromStored(settings.providerType)
        baseUrlField.text = settings.baseUrl
        apiKeyField.text = settings.apiKey
        replaceModelOptions(settings.knownModels)
        setSelectedModel(settings.defaultModel)
        toolCallingModeField.selectedItem = ToolCallingMode.fromStored(settings.toolCallingMode)
        shellPathField.text = settings.shellPath.ifBlank { IDopenSettingsState.defaultShellPath() }
        timeoutField.value = settings.commandTimeoutSeconds
        headersArea.text = settings.headersText
        lastProviderSelection = null
        refreshQuotaStatus(null)
        refreshAuthStatus()
        rebuildPanel()
        updateProviderUiState(forceModelRefresh = true)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun configureRenderers() {
        providerTypeField.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = providerLabel(value as? ProviderType ?: ProviderType.OPENAI_COMPATIBLE)
                return component
            }
        }
        toolCallingModeField.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = toolCallingModeLabel(value as? ToolCallingMode ?: ToolCallingMode.AUTO)
                return component
            }
        }
    }

    private fun bindActions() {
        providerTypeField.addActionListener { updateProviderUiState() }

        displayLanguageButton.addActionListener {
            displayLanguage = displayLanguage.toggle()
            rebuildPanel()
            refreshAuthStatus()
            updateProviderUiState()
        }

        nimPresetButton.addActionListener {
            providerTypeField.selectedItem = ProviderType.OPENAI_COMPATIBLE
            if (baseUrlField.text.isBlank()) {
                baseUrlField.text = "https://integrate.api.nvidia.com/v1"
            }
            if (selectedModel().isBlank()) {
                setSelectedModel("meta/llama-3.1-70b-instruct")
            }
            if (shellPathField.text.isBlank()) {
                shellPathField.text = IDopenSettingsState.defaultShellPath()
            }
            toolCallingModeField.selectedItem = ToolCallingMode.AUTO
            updateProviderUiState()
            Messages.showInfoMessage(
                panel,
                t("已应用 NVIDIA NIM 预设，并将工具调用切换为自动检测。", "Applied the NVIDIA NIM preset and switched tool calling to auto detection."),
                "IDopen",
            )
        }

        testConnectionButton.addActionListener {
            val config = currentProviderConfig(requireModel = false) ?: return@addActionListener
            runProviderAction(t("测试连接", "Test connection")) {
                client.testConnection(config)
            }.onSuccess { result ->
                if (selectedProviderType() == ProviderType.OPENAI_COMPATIBLE && result.models.isNotEmpty()) {
                    replaceModelOptions(result.models)
                }
                val sampleModels = result.models.take(5)
                val modelHint = if (sampleModels.isEmpty()) {
                    ""
                } else {
                    if (displayLanguage == DisplayLanguage.ZH_CN) {
                        "\n示例模型：${sampleModels.joinToString(", ")}"
                    } else {
                        "\nSample models: ${sampleModels.joinToString(", ")}"
                    }
                }
                refreshAuthStatus()
                Messages.showInfoMessage(panel, result.message + modelHint, t("连接测试", "Connection test"))
            }.onFailure { error ->
                Messages.showErrorDialog(panel, error.message ?: t("连接测试失败。", "Connection test failed."), t("连接测试", "Connection test"))
            }
        }

        fetchModelsButton.addActionListener {
            val config = currentProviderConfig(requireModel = false) ?: return@addActionListener
            runProviderAction(t("获取模型", "Fetch models")) {
                client.listModels(config)
            }.onSuccess { models ->
                if (models.isEmpty()) {
                    Messages.showWarningDialog(
                        t("提供商已响应，但没有返回可选模型。", "The provider responded, but returned no selectable models."),
                        t("获取模型", "Fetch models"),
                    )
                    return@onSuccess
                }
                replaceModelOptions(models)
                val selected = selectedModel().takeIf { it in models } ?: models.first()
                setSelectedModel(selected)
                Messages.showInfoMessage(
                    panel,
                    if (displayLanguage == DisplayLanguage.ZH_CN) {
                        "已加载 ${models.size} 个模型，并刷新模型选择器。"
                    } else {
                        "Loaded ${models.size} models and refreshed the model selector."
                    },
                    t("获取模型", "Fetch models"),
                )
            }.onFailure { error ->
                Messages.showErrorDialog(panel, error.message ?: t("获取模型失败。", "Fetching models failed."), t("获取模型", "Fetch models"))
            }
        }

        clearHeadersButton.addActionListener {
            headersArea.text = ""
        }

        loginChatGptButton.addActionListener {
            providerTypeField.selectedItem = ProviderType.CHATGPT_AUTH
            runProviderAction(t("使用 ChatGPT 登录", "Login with ChatGPT")) {
                ChatGptAuthSupport.loginWithBrowser(settings)
            }.onSuccess { status ->
                replaceModelOptions(ChatGptAuthSupport.supportedModels())
                if (selectedModel().isBlank() || selectedModel() !in ChatGptAuthSupport.supportedModels()) {
                    setSelectedModel(ChatGptAuthSupport.defaultModel())
                }
                refreshAuthStatus()
                updateProviderUiState(forceModelRefresh = true)
                Messages.showInfoMessage(panel, status.summary(displayLanguage), t("ChatGPT 登录", "ChatGPT login"))
            }.onFailure { error ->
                Messages.showErrorDialog(
                    panel,
                    (error.message ?: t("ChatGPT 登录失败。", "ChatGPT login failed.")) + "\n\n" +
                        t("如果浏览器登录持续失败，请改用“设备码登录”。", "If browser sign-in keeps failing, try \"Use device code\"."),
                    t("ChatGPT 登录", "ChatGPT login"),
                )
            }
        }

        loginChatGptDeviceButton.addActionListener {
            providerTypeField.selectedItem = ProviderType.CHATGPT_AUTH
            runProviderAction(t("开始 ChatGPT 设备码登录", "Start ChatGPT device login")) {
                ChatGptAuthSupport.startDeviceAuthorization()
            }.onSuccess { session ->
                CopyPasteManager.getInstance().setContents(StringSelection(session.userCode))
                runCatching { ChatGptAuthSupport.openVerificationUrl(session.verificationUrl) }
                Messages.showInfoMessage(
                    panel,
                    if (displayLanguage == DisplayLanguage.ZH_CN) {
                        """
                        请打开 ChatGPT 验证页面并输入以下代码：
                        ${session.userCode}

                        代码已复制到剪贴板。完成浏览器授权后，点击“确定”，IDopen 会继续完成登录。
                        """.trimIndent()
                    } else {
                        """
                        Open the ChatGPT verification page and enter this code:
                        ${session.userCode}

                        The code has been copied to your clipboard. After you finish the authorization in the browser, click OK and IDopen will complete the login.
                        """.trimIndent()
                    },
                    t("ChatGPT 设备码登录", "ChatGPT device login"),
                )
                runProviderAction(t("完成 ChatGPT 设备码登录", "Complete ChatGPT device login")) {
                    ChatGptAuthSupport.loginWithDeviceCode(session, settings)
                }.onSuccess { status ->
                    replaceModelOptions(ChatGptAuthSupport.supportedModels())
                    if (selectedModel().isBlank() || selectedModel() !in ChatGptAuthSupport.supportedModels()) {
                        setSelectedModel(ChatGptAuthSupport.defaultModel())
                    }
                    refreshAuthStatus()
                    updateProviderUiState(forceModelRefresh = true)
                    Messages.showInfoMessage(panel, status.summary(displayLanguage), t("ChatGPT 设备码登录", "ChatGPT device login"))
                }.onFailure { error ->
                    Messages.showErrorDialog(
                        panel,
                        error.message ?: t("ChatGPT 设备码登录失败。", "ChatGPT device login failed."),
                        t("ChatGPT 设备码登录", "ChatGPT device login"),
                    )
                }
            }.onFailure { error ->
                Messages.showErrorDialog(
                    panel,
                    error.message ?: t("无法开始 ChatGPT 设备码登录。", "Unable to start ChatGPT device login."),
                    t("ChatGPT 设备码登录", "ChatGPT device login"),
                )
            }
        }

        logoutChatGptButton.addActionListener {
            ChatGptAuthSupport.logout(settings)
            refreshQuotaStatus(null)
            refreshAuthStatus()
            updateProviderUiState(forceModelRefresh = true)
            Messages.showInfoMessage(panel, t("已清除本地保存的 ChatGPT 登录状态。", "Cleared the stored ChatGPT login."), t("ChatGPT 登录", "ChatGPT login"))
        }

        useChatGptModelsButton.addActionListener {
            providerTypeField.selectedItem = ProviderType.CHATGPT_AUTH
            replaceModelOptions(ChatGptAuthSupport.supportedModels())
            if (selectedModel().isBlank() || selectedModel() !in ChatGptAuthSupport.supportedModels()) {
                setSelectedModel(ChatGptAuthSupport.defaultModel())
            }
            updateProviderUiState(forceModelRefresh = true)
        }

        checkChatGptQuotaButton.addActionListener {
            providerTypeField.selectedItem = ProviderType.CHATGPT_AUTH
            runProviderAction(t("检查 ChatGPT 额度", "Check ChatGPT quota")) {
                ChatGptQuotaSupport.fetchQuotaStatus(settings)
            }.onSuccess { quota ->
                refreshQuotaStatus(quota)
                Messages.showInfoMessage(panel, quota.details(displayLanguage), t("ChatGPT 额度", "ChatGPT quota"))
            }.onFailure { error ->
                Messages.showErrorDialog(panel, error.message ?: t("获取 ChatGPT 额度失败。", "Failed to fetch ChatGPT quota."), t("ChatGPT 额度", "ChatGPT quota"))
            }
        }

        openChatGptUsagePageButton.addActionListener {
            runCatching { ChatGptQuotaSupport.openUsagePage() }
                .onFailure { error ->
                    Messages.showErrorDialog(
                        panel,
                        error.message ?: t("打开 ChatGPT 用量页面失败。", "Failed to open the ChatGPT usage page."),
                        t("ChatGPT 额度", "ChatGPT quota"),
                    )
                }
        }
    }

    private fun rebuildPanel() {
        val root = panel ?: return
        applyLocalizedTexts()

        val titleLabel = JBLabel("IDopen")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, titleLabel.font.size2D + 1f)

        val languagePanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        languagePanel.isOpaque = false
        languagePanel.add(displayLanguageHintLabel)
        languagePanel.add(displayLanguageButton)

        val topBar = JPanel(BorderLayout())
        topBar.isOpaque = false
        topBar.add(titleLabel, BorderLayout.WEST)
        topBar.add(languagePanel, BorderLayout.EAST)

        val authPanel = JPanel(BorderLayout(0, 6))
        val authStatusPanel = JPanel()
        authStatusPanel.layout = BoxLayout(authStatusPanel, BoxLayout.Y_AXIS)
        authStatusPanel.isOpaque = false
        authStatusPanel.add(authStatusLabel)
        authStatusPanel.add(quotaStatusLabel)

        val authButtons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        authButtons.isOpaque = false
        authButtons.add(loginChatGptButton)
        authButtons.add(loginChatGptDeviceButton)
        authButtons.add(logoutChatGptButton)
        authButtons.add(useChatGptModelsButton)
        authButtons.add(checkChatGptQuotaButton)
        authButtons.add(openChatGptUsagePageButton)
        authPanel.add(authStatusPanel, BorderLayout.NORTH)
        authPanel.add(authButtons, BorderLayout.CENTER)

        val headerPanel = JPanel(BorderLayout(0, 6))
        headerPanel.isOpaque = false
        headerPanel.add(JBLabel(t("自定义请求头（可选，每行一个：Header-Name: value）", "Custom headers (optional, one per line: Header-Name: value)")), BorderLayout.NORTH)
        headerPanel.add(JBScrollPane(headersArea), BorderLayout.CENTER)
        headerPanel.preferredSize = Dimension(420, 140)

        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        actionPanel.isOpaque = false
        actionPanel.add(nimPresetButton)
        actionPanel.add(testConnectionButton)
        actionPanel.add(fetchModelsButton)
        actionPanel.add(clearHeadersButton)

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(t("显示语言：", "Display language:"), buildLanguageControlRow())
            .addLabeledComponent(t("提供商：", "Provider:"), providerTypeField)
            .addLabeledComponent(t("Base URL：", "Base URL:"), baseUrlField)
            .addLabeledComponent(t("API 密钥：", "API key:"), apiKeyField)
            .addLabeledComponent(t("ChatGPT 登录：", "ChatGPT auth:"), authPanel)
            .addLabeledComponent(t("默认模型：", "Default model:"), modelField)
            .addLabeledComponent(t("工具调用：", "Tool calling:"), toolCallingModeField)
            .addLabeledComponent(t("Shell 可执行文件：", "Shell executable:"), shellPathField)
            .addLabeledComponent(t("命令超时（秒）：", "Command timeout (seconds):"), timeoutField)
            .addComponent(actionPanel)
            .addComponent(headerPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        root.removeAll()
        root.add(topBar, BorderLayout.NORTH)
        root.add(form, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    private fun buildLanguageControlRow(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.isOpaque = false
        row.add(displayLanguageButton)
        row.add(JBLabel(t("切换设置页与额度弹窗的显示语言", "Switch the settings and quota dialog language")))
        return row
    }

    private fun currentProviderConfig(requireModel: Boolean): ProviderConfig? {
        val validation = when (selectedProviderType()) {
            ProviderType.OPENAI_COMPATIBLE -> ProviderConfigSupport.fromInputs(
                baseUrl = baseUrlField.text,
                apiKey = String(apiKeyField.password),
                model = selectedModel(),
                headersText = headersArea.text,
                requireModel = requireModel,
            )

            ProviderType.CHATGPT_AUTH -> ProviderConfigSupport.fromChatGptAuth(
                accessToken = settings.chatGptAccessToken,
                refreshToken = settings.chatGptRefreshToken,
                accessTokenExpiresAt = settings.chatGptAccessTokenExpiresAt,
                accountId = settings.chatGptAccountId,
                model = selectedModel(),
                headersText = headersArea.text,
                requireModel = requireModel,
            )
        }
        if (validation.error != null) {
            Messages.showErrorDialog(panel, validation.error, t("配置不完整", "Incomplete configuration"))
        }
        return validation.config
    }

    private fun selectedModel(): String {
        val editorItem = modelField.editor.item?.toString()?.trim().orEmpty()
        return editorItem.ifBlank { modelField.selectedItem?.toString()?.trim().orEmpty() }
    }

    private fun selectedProviderType(): ProviderType {
        return providerTypeField.selectedItem as? ProviderType ?: ProviderType.OPENAI_COMPATIBLE
    }

    private fun selectedToolCallingMode(): ToolCallingMode {
        return toolCallingModeField.selectedItem as? ToolCallingMode ?: ToolCallingMode.AUTO
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

    private fun refreshAuthStatus() {
        val status = ChatGptAuthSupport.getStatus(settings)
        val expiresAt = status.expiresAt?.let {
            if (displayLanguage == DisplayLanguage.ZH_CN) {
                " | 访问令牌过期时间：${Instant.ofEpochMilli(it)}"
            } else {
                " | access expires: ${Instant.ofEpochMilli(it)}"
            }
        }.orEmpty()
        authStatusLabel.text = status.summary(displayLanguage) + expiresAt
        authStatusLabel.toolTipText = buildString {
            append(status.summary(displayLanguage))
            status.accountId?.let {
                append("\n")
                append(if (displayLanguage == DisplayLanguage.ZH_CN) "账号 ID：$it" else "Account ID: $it")
            }
        }
        if (!status.loggedIn) {
            refreshQuotaStatus(null)
        } else if (lastQuotaStatus == null) {
            refreshQuotaStatus()
        }
    }

    private fun refreshQuotaStatus(quota: ChatGptQuotaSupport.ChatGptQuotaStatus? = lastQuotaStatus) {
        lastQuotaStatus = quota
        val loggedIn = ChatGptAuthSupport.getStatus(settings).loggedIn
        quotaStatusLabel.text = when {
            !loggedIn -> t("额度：请先登录 ChatGPT", "Quota: sign in with ChatGPT first")
            quota != null -> quota.summary(displayLanguage)
            else -> t("额度：尚未检查", "Quota: not checked yet")
        }
        quotaStatusLabel.toolTipText = when {
            quota != null -> quota.details(displayLanguage)
            loggedIn -> t("点击“检查额度”以获取最新的 ChatGPT Codex 额度。", "Click \"Check quota\" to fetch the latest ChatGPT Codex quota.")
            else -> t("登录 ChatGPT 后可使用额度查询。", "Quota lookup is available after ChatGPT login.")
        }
    }

    private fun updateProviderUiState(forceModelRefresh: Boolean = false) {
        val providerType = selectedProviderType()
        val definition = ProviderDefinitionSupport.definition(providerType)
        val providerChanged = providerType != lastProviderSelection
        if (providerChanged || forceModelRefresh) {
            replaceModelOptions(definition.modelOptions(settings))
            val preferredModel = definition.preferredModel(settings)
            if (preferredModel.isNotBlank()) {
                setSelectedModel(preferredModel)
            }
        }
        lastProviderSelection = providerType
        val openAiMode = providerType == ProviderType.OPENAI_COMPATIBLE

        baseUrlField.isEnabled = !definition.managesEndpoint
        apiKeyField.isEnabled = !definition.managesCredentials
        nimPresetButton.isEnabled = providerType == ProviderType.OPENAI_COMPATIBLE

        authStatusLabel.isEnabled = definition.supportsManagedAuth
        quotaStatusLabel.isEnabled = definition.supportsQuotaLookup
        loginChatGptButton.isEnabled = definition.supportsManagedAuth
        loginChatGptDeviceButton.isEnabled = definition.supportsManagedAuth
        logoutChatGptButton.isEnabled = definition.supportsManagedAuth
        useChatGptModelsButton.isEnabled = definition.supportsManagedAuth
        checkChatGptQuotaButton.isEnabled = definition.supportsQuotaLookup
        openChatGptUsagePageButton.isEnabled = definition.supportsQuotaLookup

        baseUrlField.toolTipText = if (openAiMode) {
            t("OpenAI-compatible 提供商的 Base URL。", "Base URL for the OpenAI-compatible provider.")
        } else {
            t("ChatGPT 登录模式下会自动管理。", "Managed automatically for ChatGPT login.")
        }
        apiKeyField.toolTipText = if (openAiMode) {
            t("OpenAI-compatible 提供商使用的 API 密钥。", "API key used for the OpenAI-compatible provider.")
        } else {
            t("ChatGPT 登录会单独保存 OAuth 令牌。", "ChatGPT login stores OAuth tokens separately.")
        }
    }

    private fun applyLocalizedTexts() {
        val buttonTarget = if (displayLanguage == DisplayLanguage.ZH_CN) "English" else "中文"
        displayLanguageButton.text = buttonTarget
        displayLanguageButton.toolTipText = if (displayLanguage == DisplayLanguage.ZH_CN) {
            "切换到英文界面"
        } else {
            "Switch to Chinese"
        }
        displayLanguageHintLabel.text = if (displayLanguage == DisplayLanguage.ZH_CN) {
            "当前界面语言：中文"
        } else {
            "Current UI language: English"
        }

        nimPresetButton.text = t("使用 NVIDIA NIM 预设", "Use NVIDIA NIM preset")
        testConnectionButton.text = t("测试连接", "Test connection")
        fetchModelsButton.text = t("获取模型", "Fetch models")
        clearHeadersButton.text = t("清空请求头", "Clear headers")
        loginChatGptButton.text = t("使用 ChatGPT 登录", "Login with ChatGPT")
        loginChatGptDeviceButton.text = t("设备码登录", "Use device code")
        logoutChatGptButton.text = t("退出登录", "Log out")
        useChatGptModelsButton.text = t("使用 GPT 模型", "Use GPT models")
        checkChatGptQuotaButton.text = t("检查额度", "Check quota")
        openChatGptUsagePageButton.text = t("打开用量页面", "Open usage page")

        headersArea.toolTipText = t("可选请求头，每行一个：Header-Name: value", "Optional request headers. One per line: Header-Name: value")
        providerTypeField.repaint()
        toolCallingModeField.repaint()
    }

    private fun <T> runProviderAction(title: String, action: () -> T): Result<T> {
        var result: Result<T>? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { result = runCatching(action) },
            title,
            true,
            null,
        )
        return result ?: Result.failure(IllegalStateException("$title did not run."))
    }

    private fun providerLabel(type: ProviderType): String {
        return when (type) {
            ProviderType.OPENAI_COMPATIBLE -> t("OpenAI 兼容接口", "OpenAI-compatible")
            ProviderType.CHATGPT_AUTH -> t("ChatGPT 账号（GPT 授权）", "ChatGPT account (GPT auth)")
        }
    }

    private fun toolCallingModeLabel(mode: ToolCallingMode): String {
        return when (mode) {
            ToolCallingMode.AUTO -> t("自动（推荐）", "Auto (recommended)")
            ToolCallingMode.ENABLED -> t("强制开启", "Force enabled")
            ToolCallingMode.DISABLED -> t("强制关闭", "Force disabled")
        }
    }

    private fun t(zh: String, en: String): String = localize(zh, en)

    private fun localize(zh: String, en: String): String {
        if (displayLanguage != DisplayLanguage.ZH_CN) {
            return en
        }
        return if (looksLikeMojibake(zh)) en else zh
    }

    private fun looksLikeMojibake(value: String): Boolean {
        if (value.contains('\uFFFD')) return true
        val markers = listOf("锛", "銆", "鏈", "璇", "缂", "鍒", "鍦", "鐢", "宸", "闈", "澶", "鍚", "鍙", "妫", "椤", "瀛", "鎵", "鏃", "鍐", "绠")
        return markers.count { value.contains(it) } >= 2
    }
}
