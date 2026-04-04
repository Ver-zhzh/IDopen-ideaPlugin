package com.idopen.idopen.settings

import com.idopen.idopen.agent.LoadedMcpServer
import com.idopen.idopen.agent.McpSupport
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

class IDopenMcpConfigurable : Configurable {
    companion object {
        private val LOG = Logger.getInstance(IDopenMcpConfigurable::class.java)
    }

    private data class ProjectChoice(
        val project: Project?,
        val label: String,
    ) {
        override fun toString(): String = label
    }

    private enum class McpTarget {
        USER,
        PROJECT,
    }

    private val settings = IDopenSettingsState.getInstance()

    private val introLabel = JBLabel()
    private val statusValueLabel = JBLabel()
    private val statusHintArea = createReadOnlyArea(rows = 3)
    private val userConfigPathArea = createReadOnlyArea(rows = 2)
    private val projectConfigPathArea = createReadOnlyArea(rows = 2)
    private val editorPathLabel = JBLabel()
    private val editorHintLabel = JBLabel()
    private val editorHost = JPanel(BorderLayout())
    private val fallbackEditorArea = JBTextArea()
    private val serversSummaryLabel = JBLabel()
    private val serversContainer = JPanel()
    private val overviewPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(12)))

    private val targetField = JComboBox(arrayOf(McpTarget.USER, McpTarget.PROJECT))
    private val projectField = JComboBox<ProjectChoice>()
    private val saveButton = JButton()
    private val validateButton = JButton()
    private val reloadButton = JButton()
    private val openInEditorButton = JButton()
    private val emptyTemplateButton = JButton()
    private val stdioTemplateButton = JButton()
    private val httpTemplateButton = JButton()
    private val formatButton = JButton()
    private val refreshStatusButton = JButton()

    private var panel: JPanel? = null
    private var loadedDocument: McpConfigDocument? = null
    private var editorDocument: Document? = null
    private var editorField: EditorTextField? = null
    private lateinit var contentSplit: JSplitPane
    private lateinit var editorCard: JComponent
    private lateinit var serversCard: JComponent
    private var selectedProjectPath: String? = null

    override fun getDisplayName(): String = "IDopen MCP"

    override fun createComponent(): JComponent {
        if (panel != null) return panel!!

        return runCatching {
            configureComponents()
            applyTexts()
            bindActions()

            editorCard = buildEditorCard()
            serversCard = buildDetectedServersCard()
            contentSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorCard, serversCard).apply {
                resizeWeight = 0.64
                dividerSize = JBUI.scale(10)
                border = BorderFactory.createEmptyBorder()
                setContinuousLayout(true)
            }

            panel = JPanel(BorderLayout(0, JBUI.scale(14))).apply {
                border = JBUI.Borders.empty(16)
                add(buildTopPanel(), BorderLayout.NORTH)
                add(contentSplit, BorderLayout.CENTER)
            }

            installResponsiveBehavior()
            SwingUtilities.invokeLater { initializeAfterCreate() }
            panel!!
        }.getOrElse { error ->
            LOG.error("Failed to create IDopen MCP settings UI", error)
            val errorPanel = buildErrorPanel(error)
            panel = errorPanel
            errorPanel
        }
    }

    override fun isModified(): Boolean {
        val current = normalizeEditorText(currentEditorText())
        val loaded = normalizeEditorText(loadedDocument?.text.orEmpty())
        return current != loaded
    }

    override fun apply() {
        saveCurrentConfig(showDialog = false)
        refreshView(loadEditor = false)
    }

    override fun reset() {
        if (panel == null) return
        SwingUtilities.invokeLater { initializeAfterCreate() }
    }

    override fun disposeUIResources() {
        panel = null
        loadedDocument = null
        editorField = null
        editorDocument = null
        editorHost.removeAll()
    }

    private fun buildErrorPanel(error: Throwable): JPanel {
        val title = JBLabel(t("IDopen MCP 页面加载失败", "Failed to load the IDopen MCP page")).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        }
        val body = createReadOnlyArea(rows = 8).apply {
            text = buildString {
                appendLine(t("设置页初始化时发生异常。", "The settings page threw an exception during initialization."))
                appendLine()
                appendLine("${error::class.java.simpleName}: ${error.message.orEmpty()}")
                error.stackTrace.take(8).forEach { appendLine("at $it") }
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(16)
            add(title, BorderLayout.NORTH)
            add(JBScrollPane(body), BorderLayout.CENTER)
        }
    }

    private fun initializeAfterCreate() {
        runCatching {
            ensureEditorComponent()
            refreshView(loadEditor = true)
            applyResponsiveLayout()
        }.onFailure { error ->
            LOG.error("Failed to initialize IDopen MCP settings content", error)
            showInlineInitializationError(error)
        }
    }

    private fun showInlineInitializationError(error: Throwable) {
        statusValueLabel.text = t("页面初始化失败", "Failed to initialize the page")
        statusHintArea.text = "${error::class.java.simpleName}: ${error.message.orEmpty()}"
        serversSummaryLabel.text = t("初始化失败", "Initialization failed")
        editorPathLabel.text = t("编辑路径：初始化失败", "Editing path: initialization failed")
        editorHintLabel.text = t("请将当前错误信息反馈回来。", "Please send this error back for diagnosis.")
        editorHost.removeAll()
        editorHost.add(
            JBScrollPane(
                createReadOnlyArea(rows = 12).apply {
                    text = buildString {
                        appendLine("${error::class.java.simpleName}: ${error.message.orEmpty()}")
                        error.stackTrace.take(8).forEach { appendLine("at $it") }
                    }
                },
            ),
            BorderLayout.CENTER,
        )
        editorHost.revalidate()
        editorHost.repaint()
        serversContainer.removeAll()
        serversContainer.add(
            createEmptyStateCard(
                title = t("内容加载失败", "Content failed to load"),
                lines = listOf(
                    t("设置页主体已经返回，但初始化阶段抛出了异常。", "The settings page rendered, but initialization failed afterward."),
                    "${error::class.java.simpleName}: ${error.message.orEmpty()}",
                ),
            ),
        )
        serversContainer.revalidate()
        serversContainer.repaint()
    }

    private fun configureComponents() {
        introLabel.foreground = mutedForeground()
        statusValueLabel.font = statusValueLabel.font.deriveFont(Font.BOLD, statusValueLabel.font.size2D + 1f)
        editorPathLabel.foreground = mutedForeground()
        editorHintLabel.foreground = mutedForeground()
        serversSummaryLabel.foreground = mutedForeground()
        editorHost.isOpaque = false
        fallbackEditorArea.rows = 22
        fallbackEditorArea.columns = 88
        fallbackEditorArea.font = Font(Font.MONOSPACED, Font.PLAIN, fallbackEditorArea.font.size)
        fallbackEditorArea.lineWrap = false
        fallbackEditorArea.wrapStyleWord = false
        fallbackEditorArea.tabSize = 2
        fallbackEditorArea.border = JBUI.Borders.empty(8)

        serversContainer.layout = BoxLayout(serversContainer, BoxLayout.Y_AXIS)
        serversContainer.isOpaque = false
        overviewPanel.isOpaque = false

        targetField.preferredSize = Dimension(JBUI.scale(180), targetField.preferredSize.height)
        targetField.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when (value as? McpTarget ?: McpTarget.USER) {
                    McpTarget.USER -> t("用户级配置", "User scope")
                    McpTarget.PROJECT -> t("项目级配置", "Project scope")
                }
                return component
            }
        }
        projectField.preferredSize = Dimension(JBUI.scale(280), projectField.preferredSize.height)
        projectField.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? ProjectChoice)?.label ?: "Select project"
                return component
            }
        }
    }

    private fun createEditorField(document: Document): EditorTextField {
        return object : EditorTextField(document, null, PlainTextFileType.INSTANCE, false, false) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor() as EditorEx
                editor.settings.isLineNumbersShown = true
                editor.settings.isIndentGuidesShown = true
                editor.settings.isFoldingOutlineShown = false
                editor.settings.additionalColumnsCount = 2
                editor.settings.additionalLinesCount = 1
                editor.settings.isWhitespacesShown = false
                editor.settings.isRightMarginShown = false
                editor.setHorizontalScrollbarVisible(true)
                editor.setVerticalScrollbarVisible(true)
                editor.setBorder(BorderFactory.createEmptyBorder())
                return editor
            }
        }.apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(540))
            minimumSize = Dimension(JBUI.scale(520), JBUI.scale(320))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xD9DDE3, 0x3F434A)),
                JBUI.Borders.empty(2),
            )
        }
    }

    private fun ensureEditorComponent() {
        if (editorField != null || editorHost.componentCount > 0) return
        val component = runCatching {
            val document = EditorFactory.getInstance().createDocument("")
            editorDocument = document
            createEditorField(document).also { editorField = it }
        }.getOrElse {
            JBScrollPane(
                fallbackEditorArea,
                JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
            ).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(0xD9DDE3, 0x3F434A)),
                    JBUI.Borders.empty(),
                )
                preferredSize = Dimension(JBUI.scale(760), JBUI.scale(540))
                minimumSize = Dimension(JBUI.scale(520), JBUI.scale(320))
            }
        }
        editorHost.removeAll()
        editorHost.add(component, BorderLayout.CENTER)
    }

    private fun applyTexts() {
        introLabel.text = t(
            "在这里集中维护 MCP 配置。用户级适合全局服务，项目级适合当前仓库；保存前可先校验，右侧会展示当前识别到的服务。",
            "Manage MCP config here. Use User for global services and Project for repository-specific services. Validate before saving, and review the detected servers on the right.",
        )

        saveButton.text = t("保存", "Save")
        validateButton.text = t("校验", "Validate")
        reloadButton.text = t("重新加载", "Reload")
        openInEditorButton.text = t("在 IDE 中打开", "Open in IDE")
        emptyTemplateButton.text = t("空模板", "Empty template")
        stdioTemplateButton.text = t("Stdio 示例", "Stdio example")
        httpTemplateButton.text = t("HTTP 示例", "HTTP example")
        formatButton.text = t("格式化 JSON", "Format JSON")
        refreshStatusButton.text = t("刷新状态", "Refresh status")
        editorHintLabel.text = t(
            "内嵌 IDE 编辑器，适合直接修改 JSON；保存前建议先校验或格式化。",
            "Embedded IDE editor for direct JSON editing. Validate or format before saving when needed.",
        )
    }

    private fun bindActions() {
        targetField.addActionListener { loadSelectedTarget() }
        projectField.addActionListener {
            selectedProjectPath = selectedProjectChoice()?.project?.basePath?.trim()?.takeIf { it.isNotBlank() }
            refreshView(loadEditor = true)
        }
        reloadButton.addActionListener { loadSelectedTarget() }
        validateButton.addActionListener {
            val validation = runCatching { McpConfigEditorSupport.validateConfigText(currentEditorText()) }
                .onFailure { error ->
                    Messages.showErrorDialog(
                        error.message ?: t("MCP 配置无效。", "Invalid MCP config."),
                        "IDopen MCP",
                    )
                }
                .getOrNull()
                ?: return@addActionListener
            Messages.showInfoMessage(validation.summary(), "IDopen MCP")
        }
        saveButton.addActionListener { saveCurrentConfig(showDialog = true) }
        openInEditorButton.addActionListener {
            val project = currentProject()
                ?: return@addActionListener Messages.showWarningDialog(
                    t("请先打开一个项目，再在 IDE 中打开 MCP 配置文件。", "Open a project first so the MCP config can be opened inside the IDE."),
                    "IDopen MCP",
                )
            val path = currentTargetPath()
                ?: return@addActionListener Messages.showWarningDialog(
                    t("当前目标不可用。", "The current MCP target is unavailable."),
                    "IDopen MCP",
                )
            runCatching { McpConfigEditorSupport.openConfig(project, path) }
                .onFailure { error ->
                    Messages.showErrorDialog(
                        error.message ?: t("无法在 IDE 中打开 MCP 配置。", "Failed to open the MCP config in the IDE."),
                        "IDopen MCP",
                    )
                }
        }
        emptyTemplateButton.addActionListener { updateEditorText(McpConfigEditorSupport.emptyTemplate()) }
        stdioTemplateButton.addActionListener { updateEditorText(McpConfigEditorSupport.stdioExampleTemplate()) }
        httpTemplateButton.addActionListener { updateEditorText(McpConfigEditorSupport.httpExampleTemplate()) }
        formatButton.addActionListener {
            runCatching { McpConfigEditorSupport.formatConfigText(currentEditorText()) }
                .onSuccess(::updateEditorText)
                .onFailure { error ->
                    Messages.showErrorDialog(
                        error.message ?: t("JSON 格式化失败。", "Failed to format the JSON."),
                        "IDopen MCP",
                    )
                }
        }
        refreshStatusButton.addActionListener { refreshView(loadEditor = false) }
    }

    private fun buildTopPanel(): JComponent {
        val headerTitle = JBLabel("IDopen MCP").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerTitle)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(introLabel)
        }

        overviewPanel.removeAll()
        overviewPanel.add(
            createOverviewCard(
                title = t("状态概览", "Status"),
                content = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(statusValueLabel)
                    add(Box.createVerticalStrut(JBUI.scale(6)))
                    add(statusHintArea)
                },
            ),
        )
        overviewPanel.add(createOverviewCard(t("用户配置", "User config"), userConfigPathArea))
        overviewPanel.add(createOverviewCard(t("项目配置", "Project config"), projectConfigPathArea))

        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(buildPrimaryActionRow())
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(buildTemplateActionRow())
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(Box.createVerticalStrut(JBUI.scale(14)))
            add(overviewPanel)
            add(Box.createVerticalStrut(JBUI.scale(14)))
            add(actions)
        }
    }

    private fun buildPrimaryActionRow(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel(t("编辑目标", "Target")))
            add(targetField)
            add(JBLabel("Project"))
            add(projectField)
            add(reloadButton)
            add(validateButton)
            add(saveButton)
            add(openInEditorButton)
        }
    }

    private fun buildTemplateActionRow(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel(t("模板与检查", "Templates and refresh")))
            add(emptyTemplateButton)
            add(stdioTemplateButton)
            add(httpTemplateButton)
            add(formatButton)
            add(refreshStatusButton)
        }
    }

    private fun buildEditorCard(): JComponent {
        val titleLabel = JBLabel(t("配置编辑器", "Config editor")).apply {
            font = font.deriveFont(Font.BOLD)
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(editorPathLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(editorHintLabel)
        }

        val wrapper = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(6)
                    add(editorHost, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

        return createCard(null, wrapper)
    }

    private fun buildDetectedServersCard(): JComponent {
        val header = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(
                JBLabel(t("已检测服务", "Detected servers")).apply {
                    font = font.deriveFont(Font.BOLD)
                },
                BorderLayout.NORTH,
            )
            add(serversSummaryLabel, BorderLayout.CENTER)
        }

        val scroll = JBScrollPane(serversContainer).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.isOpaque = false
            isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(540))
        }

        return createCard(null, JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        })
    }

    private fun refreshView(loadEditor: Boolean) {
        refreshProjectChoices()
        val userHome = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize()
        val userPath = McpConfigEditorSupport.preferredUserConfigPath(userHome)
        userConfigPathArea.text = userPath.toDisplayString()
        userConfigPathArea.toolTipText = userPath.toDisplayString()

        val project = currentProject()
        val projectRoot = project?.let(::currentProjectRoot)
        val servers = if (projectRoot != null) McpSupport.available(projectRoot, userHome) else emptyList()

        if (projectRoot == null) {
            projectConfigPathArea.text = t("打开项目后可用", "Available after opening a project")
            projectConfigPathArea.toolTipText = null
            statusValueLabel.text = t("未检测到已打开项目", "No open project detected")
            statusHintArea.text = t(
                "当前仍可维护用户级 MCP 配置；项目级配置会在打开项目后自动生效。",
                "You can still maintain the user-scoped MCP config. Project-scoped config becomes available once a project is opened.",
            )
        } else {
            val projectPath = McpConfigEditorSupport.preferredProjectConfigPath(projectRoot)
            projectConfigPathArea.text = projectPath.toDisplayString()
            projectConfigPathArea.toolTipText = projectPath.toDisplayString()
            statusValueLabel.text = if (servers.isEmpty()) {
                t("尚未检测到 MCP 服务", "No MCP servers detected yet")
            } else {
                t("已检测到 ${servers.size} 个 MCP 服务", "Detected ${servers.size} MCP server(s)")
            }
            statusHintArea.text = if (servers.isEmpty()) {
                t(
                    "先在左侧编辑用户级或项目级配置，保存后再刷新状态，右侧会列出当前生效的服务。",
                    "Edit the user or project config on the left, save it, and then refresh. Active servers will appear on the right.",
                )
            } else {
                t(
                    "当前配置已被识别。你可以继续调整 JSON，并随时刷新右侧列表确认最终生效结果。",
                    "The current config has been recognized. Keep editing the JSON and refresh the server list to confirm the final active state.",
                )
            }
        }

        renderServerList(projectRoot, servers)
        if (loadEditor || loadedDocument == null) {
            loadSelectedTarget()
        }
    }

    private fun renderServerList(projectRoot: Path?, servers: List<LoadedMcpServer>) {
        serversContainer.removeAll()

        when {
            projectRoot == null -> {
                serversSummaryLabel.text = t("项目级服务暂不可用", "Project-scoped services are unavailable")
                serversContainer.add(
                    createEmptyStateCard(
                        title = t("打开任意项目后再查看项目级 MCP 服务", "Open a project to inspect project-scoped MCP services"),
                        lines = listOf(
                            t("用户级配置仍然可以直接编辑和保存。", "User-scoped config can still be edited and saved."),
                            t("项目级配置路径会在打开项目后自动切换到仓库内。", "The project path will switch to the repository once a project is open."),
                        ),
                    ),
                )
            }

            servers.isEmpty() -> {
                serversSummaryLabel.text = t("还没有可用服务", "No active servers yet")
                serversContainer.add(
                    createEmptyStateCard(
                        title = t("还没有识别到可用的 MCP server", "No active MCP server has been recognized yet"),
                        lines = listOf(
                            t("1. 选择上方的编辑目标。", "1. Choose a target from the selector above."),
                            t("2. 直接编辑 JSON，或插入 Stdio / HTTP 示例模板。", "2. Edit the JSON directly, or insert a Stdio / HTTP example template."),
                            t("3. 先校验，再保存。", "3. Validate first, then save."),
                            t("4. 点击“刷新状态”确认最终生效结果。", "4. Click Refresh status to confirm what is active."),
                        ),
                    ),
                )
            }

            else -> {
                serversSummaryLabel.text = t("当前已生效 ${servers.size} 个服务", "${servers.size} server(s) are currently active")
                servers.forEachIndexed { index, server ->
                    serversContainer.add(createServerCard(server))
                    if (index < servers.lastIndex) {
                        serversContainer.add(Box.createVerticalStrut(JBUI.scale(10)))
                    }
                }
            }
        }

        serversContainer.revalidate()
        serversContainer.repaint()
    }

    private fun createServerCard(server: LoadedMcpServer): JComponent {
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(
                JBLabel(server.name).apply {
                    font = font.deriveFont(Font.BOLD)
                },
            )
            add(createBadge(server.scope.name.lowercase()))
            add(createBadge(server.transport))
        }

        val details = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(createDetailArea(t("来源", "Source"), server.sourcePath.toDisplayString()))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createDetailArea(t("地址", "Target"), server.url ?: serverCommandLine(server)))
            if (server.env.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(createDetailArea(t("环境变量", "Env"), server.env.keys.sorted().joinToString(", ")))
            }
            if (server.headers.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(createDetailArea(t("请求头", "Headers"), server.headers.keys.sorted().joinToString(", ")))
            }
            if (server.oauthScopes.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(createDetailArea(t("OAuth 范围", "OAuth scopes"), server.oauthScopes.joinToString(", ")))
            }
        }

        return createCard(null, JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(details, BorderLayout.CENTER)
        })
    }

    private fun createEmptyStateCard(title: String, lines: List<String>): JComponent {
        val titleLabel = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
        }
        val linesPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            lines.forEachIndexed { index, line ->
                add(JBLabel(line).apply { foreground = mutedForeground() })
                if (index < lines.lastIndex) {
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                }
            }
        }

        return createCard(null, JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(linesPanel, BorderLayout.CENTER)
        })
    }

    private fun createOverviewCard(title: String, content: JComponent): JComponent {
        return createCard(title, content).apply {
            preferredSize = Dimension(JBUI.scale(360), JBUI.scale(150))
            minimumSize = Dimension(JBUI.scale(280), JBUI.scale(140))
        }
    }

    private fun createCard(title: String?, content: JComponent): JPanel {
        val card = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xD9DDE3, 0x43454A)),
                JBUI.Borders.empty(12),
            )
        }
        if (!title.isNullOrBlank()) {
            card.add(
                JBLabel(title).apply {
                    font = font.deriveFont(Font.BOLD)
                },
                BorderLayout.NORTH,
            )
        }
        card.add(content, BorderLayout.CENTER)
        return card
    }

    private fun installResponsiveBehavior() {
        panel?.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                applyResponsiveLayout()
            }
        })
        SwingUtilities.invokeLater { applyResponsiveLayout() }
    }

    private fun applyResponsiveLayout() {
        val root = panel ?: return
        val width = root.width.takeIf { it > 0 } ?: return
        val narrow = width < JBUI.scale(1320)
        val desiredOrientation = if (narrow) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
        if (contentSplit.orientation != desiredOrientation) {
            contentSplit.orientation = desiredOrientation
        }

        if (narrow) {
            editorCard.minimumSize = Dimension(0, JBUI.scale(340))
            serversCard.minimumSize = Dimension(0, JBUI.scale(240))
            SwingUtilities.invokeLater {
                if (contentSplit.orientation == JSplitPane.VERTICAL_SPLIT && contentSplit.height > 0) {
                    contentSplit.dividerLocation = (contentSplit.height * 0.62).toInt()
                }
            }
        } else {
            editorCard.minimumSize = Dimension(JBUI.scale(620), 0)
            serversCard.minimumSize = Dimension(JBUI.scale(360), 0)
            SwingUtilities.invokeLater {
                if (contentSplit.orientation == JSplitPane.HORIZONTAL_SPLIT && contentSplit.width > 0) {
                    contentSplit.dividerLocation = (contentSplit.width * 0.64).toInt()
                }
            }
        }

        root.revalidate()
        root.repaint()
    }

    private fun loadSelectedTarget() {
        val document = when (selectedTarget()) {
            McpTarget.USER -> McpConfigEditorSupport.loadUserConfig()
            McpTarget.PROJECT -> {
                val projectRoot = currentProject()?.let(::currentProjectRoot)
                if (projectRoot == null) {
                    Messages.showWarningDialog(
                        t("Please select a project before editing or saving project-scoped MCP config.", "Choose a project first before editing or saving project-scoped MCP config."),
                        "IDopen MCP",
                    )
                    return
                }
                McpConfigEditorSupport.loadProjectConfig(projectRoot)
            }
        }
        loadedDocument = document
        editorPathLabel.text = t("编辑路径：", "Editing path: ") + document.path.toDisplayString()
        updateEditorText(document.text)
    }

    private fun saveCurrentConfig(showDialog: Boolean) {
        val path = currentTargetPath() ?: return
        runCatching {
            McpConfigEditorSupport.saveConfig(path, currentEditorText())
            loadedDocument = McpConfigEditorSupport.loadConfig(path)
            editorPathLabel.text = t("编辑路径：", "Editing path: ") + path.toDisplayString()
        }.onSuccess {
            refreshView(loadEditor = false)
            if (showDialog) {
                Messages.showInfoMessage(
                    t("MCP 配置已保存到：\n${path.toDisplayString()}", "Saved MCP config to:\n${path.toDisplayString()}"),
                    "IDopen MCP",
                )
            }
        }.onFailure { error ->
            Messages.showErrorDialog(
                error.message ?: t("保存 MCP 配置失败。", "Failed to save the MCP config."),
                "IDopen MCP",
            )
        }
    }

    private fun updateEditorText(text: String) {
        val normalized = sanitizeEditorText(text)
        editorDocument?.let { document ->
            WriteAction.run<RuntimeException> {
                document.setText(normalized)
            }
        } ?: run {
            fallbackEditorArea.text = normalized
        }
        editorField?.editor?.caretModel?.moveToOffset(0)
        editorField?.editor?.scrollingModel?.scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE)
        if (editorField == null) {
            fallbackEditorArea.caretPosition = 0
        }
    }

    private fun currentEditorText(): String {
        return sanitizeEditorText(editorDocument?.text ?: fallbackEditorArea.text)
    }

    private fun selectedTarget(): McpTarget {
        return targetField.selectedItem as? McpTarget ?: McpTarget.USER
    }

    private fun currentTargetPath(): Path? {
        return when (selectedTarget()) {
            McpTarget.USER -> McpConfigEditorSupport.preferredUserConfigPath(
                Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize(),
            )

            McpTarget.PROJECT -> currentProject()
                ?.let(::currentProjectRoot)
                ?.let(McpConfigEditorSupport::preferredProjectConfigPath)
        }
    }

    private fun currentProject(): Project? {
        return selectedProjectChoice()?.project
    }

    private fun refreshProjectChoices() {
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed && !it.basePath.isNullOrBlank() }
            .sortedBy { it.name.lowercase() }
        val rememberedPath = selectedProjectPath
        val choices = buildList {
            if (openProjects.size != 1) {
                add(ProjectChoice(null, "Select project"))
            }
            openProjects.forEach { project ->
                val basePath = project.basePath?.trim().orEmpty()
                add(
                    ProjectChoice(
                        project = project,
                        label = if (basePath.isBlank()) {
                            project.name
                        } else {
                            "${project.name}  (${basePath.replace('\\', '/')})"
                        },
                    ),
                )
            }
        }

        projectField.removeAllItems()
        choices.forEach(projectField::addItem)

        val selectedProject = openProjects.firstOrNull { it.basePath == rememberedPath }
            ?: openProjects.singleOrNull()
        selectedProjectPath = selectedProject?.basePath?.trim()?.takeIf { it.isNotBlank() }
        projectField.selectedItem = choices.firstOrNull { it.project?.basePath == selectedProjectPath }
            ?: choices.firstOrNull()
        projectField.isEnabled = openProjects.isNotEmpty()
    }

    private fun selectedProjectChoice(): ProjectChoice? {
        return projectField.selectedItem as? ProjectChoice
    }

    private fun currentProjectRoot(project: Project): Path? {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) return null
        return runCatching { Path.of(basePath).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun createBadge(text: String): JComponent {
        return JBLabel(text).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xC7CDD6, 0x4D5158)),
                JBUI.Borders.empty(2, 6),
            )
            foreground = mutedForeground()
        }
    }

    private fun createDetailArea(label: String, value: String): JComponent {
        return JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            columns = 28
            border = BorderFactory.createEmptyBorder()
            text = "$label: $value"
            foreground = mutedForeground()
        }
    }

    private fun createReadOnlyArea(rows: Int): JBTextArea {
        return JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            this.rows = rows
            border = BorderFactory.createEmptyBorder()
            foreground = mutedForeground()
        }
    }

    private fun serverCommandLine(server: LoadedMcpServer): String {
        return buildString {
            append(server.command.orEmpty())
            if (server.args.isNotEmpty()) {
                append(" ")
                append(server.args.joinToString(" "))
            }
        }.trim()
    }

    private fun mutedForeground() = UIManager.getColor("Label.disabledForeground") ?: JBColor.GRAY

    private fun normalizeEditorText(value: String): String {
        return sanitizeEditorText(value).trimEnd()
    }

    private fun sanitizeEditorText(value: String): String {
        return value
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private fun Path.toDisplayString(): String {
        return toString().replace('\\', '/')
    }

    private fun t(zh: String, en: String): String {
        if (DisplayLanguage.fromStored(settings.displayLanguage) != DisplayLanguage.ZH_CN) {
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
