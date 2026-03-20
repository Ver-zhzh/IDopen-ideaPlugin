package com.idopen.idopen.settings

import com.idopen.idopen.agent.LoadedMcpServer
import com.idopen.idopen.agent.McpSupport
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
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
import javax.swing.UIManager

class IDopenMcpConfigurable : Configurable {
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
    private val editorArea = JBTextArea()
    private val serversSummaryLabel = JBLabel()
    private val serversContainer = JPanel()

    private val targetField = JComboBox(arrayOf(McpTarget.USER, McpTarget.PROJECT))
    private val saveButton = JButton()
    private val validateButton = JButton()
    private val reloadButton = JButton()
    private val openInEditorButton = JButton()
    private val emptyTemplateButton = JButton()
    private val stdioTemplateButton = JButton()
    private val httpTemplateButton = JButton()
    private val refreshStatusButton = JButton()

    private var panel: JPanel? = null
    private var loadedDocument: McpConfigDocument? = null

    override fun getDisplayName(): String = "IDopen MCP"

    override fun createComponent(): JComponent {
        if (panel != null) return panel!!

        configureComponents()
        applyTexts()
        bindActions()

        val contentSplit = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            buildEditorCard(),
            buildDetectedServersCard(),
        ).apply {
            resizeWeight = 0.62
            dividerSize = JBUI.scale(10)
            border = BorderFactory.createEmptyBorder()
            setContinuousLayout(true)
        }

        panel = JPanel(BorderLayout(0, JBUI.scale(14))).apply {
            border = JBUI.Borders.empty(16)
            add(buildTopPanel(), BorderLayout.NORTH)
            add(contentSplit, BorderLayout.CENTER)
        }

        refreshView(loadEditor = true)
        return panel!!
    }

    override fun isModified(): Boolean {
        val current = normalizeEditorText(editorArea.text)
        val loaded = normalizeEditorText(loadedDocument?.text.orEmpty())
        return current != loaded
    }

    override fun apply() {
        saveCurrentConfig(showDialog = false)
        refreshView(loadEditor = false)
    }

    override fun reset() {
        refreshView(loadEditor = true)
    }

    override fun disposeUIResources() {
        panel = null
        loadedDocument = null
    }

    private fun configureComponents() {
        introLabel.foreground = mutedForeground()

        statusValueLabel.font = statusValueLabel.font.deriveFont(Font.BOLD, statusValueLabel.font.size2D + 1f)
        serversSummaryLabel.foreground = mutedForeground()
        editorPathLabel.foreground = mutedForeground()

        editorArea.rows = 22
        editorArea.font = Font(Font.MONOSPACED, Font.PLAIN, editorArea.font.size)
        editorArea.lineWrap = false
        editorArea.wrapStyleWord = false
        editorArea.tabSize = 2

        serversContainer.layout = BoxLayout(serversContainer, BoxLayout.Y_AXIS)
        serversContainer.isOpaque = false

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
    }

    private fun applyTexts() {
        introLabel.text = t(
            "在这里集中维护 MCP 配置。用户级适合全局服务，项目级适合当前仓库；保存前可先校验，右侧会实时展示当前识别到的服务。",
            "Manage MCP config here. Use User for global services and Project for repository-specific services. Validate before saving, and review the detected servers on the right.",
        )

        saveButton.text = t("保存", "Save")
        validateButton.text = t("校验", "Validate")
        reloadButton.text = t("重新加载", "Reload")
        openInEditorButton.text = t("在 IDE 中打开", "Open in IDE")
        emptyTemplateButton.text = t("空模板", "Empty template")
        stdioTemplateButton.text = t("Stdio 示例", "Stdio example")
        httpTemplateButton.text = t("HTTP 示例", "HTTP example")
        refreshStatusButton.text = t("刷新状态", "Refresh status")
    }

    private fun bindActions() {
        targetField.addActionListener { loadSelectedTarget() }
        reloadButton.addActionListener { loadSelectedTarget() }
        validateButton.addActionListener {
            val validation = runCatching { McpConfigEditorSupport.validateConfigText(editorArea.text) }
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
        emptyTemplateButton.addActionListener { editorArea.text = McpConfigEditorSupport.emptyTemplate() }
        stdioTemplateButton.addActionListener { editorArea.text = McpConfigEditorSupport.stdioExampleTemplate() }
        httpTemplateButton.addActionListener { editorArea.text = McpConfigEditorSupport.httpExampleTemplate() }
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

        val overview = JPanel(GridLayout(1, 3, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(
                createCard(
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
            add(createCard(title = t("用户配置", "User config"), content = userConfigPathArea))
            add(createCard(title = t("项目配置", "Project config"), content = projectConfigPathArea))
        }

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
            add(overview)
            add(Box.createVerticalStrut(JBUI.scale(14)))
            add(actions)
        }
    }

    private fun buildPrimaryActionRow(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel(t("编辑目标", "Target")))
            add(targetField)
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
            add(refreshStatusButton)
        }
    }

    private fun buildEditorCard(): JComponent {
        val header = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(
                JBLabel(t("配置编辑器", "Config editor")).apply {
                    font = font.deriveFont(Font.BOLD)
                },
                BorderLayout.NORTH,
            )
            add(editorPathLabel, BorderLayout.CENTER)
        }

        val editorScroll = JBScrollPane(
            editorArea,
            JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        ).apply {
            border = BorderFactory.createEmptyBorder()
        }

        return createCard(title = null, content = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(editorScroll, BorderLayout.CENTER)
        })
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
        }

        return createCard(title = null, content = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        })
    }

    private fun refreshView(loadEditor: Boolean) {
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
            if (selectedTarget() == McpTarget.PROJECT) {
                targetField.selectedItem = McpTarget.USER
            }
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
            add(createDetailLabel(t("来源", "Source"), server.sourcePath.toDisplayString()))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createDetailLabel(t("地址", "Target"), server.url ?: serverCommandLine(server)))
            if (server.env.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(createDetailLabel(t("环境变量", "Env"), server.env.keys.sorted().joinToString(", ")))
            }
            if (server.headers.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(createDetailLabel(t("请求头", "Headers"), server.headers.keys.sorted().joinToString(", ")))
            }
            if (server.oauthScopes.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(createDetailLabel(t("OAuth 范围", "OAuth scopes"), server.oauthScopes.joinToString(", ")))
            }
        }

        return createCard(title = null, content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
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

        return createCard(title = null, content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(linesPanel, BorderLayout.CENTER)
        })
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

    private fun loadSelectedTarget() {
        val document = when (selectedTarget()) {
            McpTarget.USER -> McpConfigEditorSupport.loadUserConfig()
            McpTarget.PROJECT -> {
                val projectRoot = currentProject()?.let(::currentProjectRoot)
                if (projectRoot == null) {
                    targetField.selectedItem = McpTarget.USER
                    McpConfigEditorSupport.loadUserConfig()
                } else {
                    McpConfigEditorSupport.loadProjectConfig(projectRoot)
                }
            }
        }
        loadedDocument = document
        editorPathLabel.text = t("编辑路径：", "Editing path: ") + document.path.toDisplayString()
        editorArea.text = document.text
        editorArea.caretPosition = 0
    }

    private fun saveCurrentConfig(showDialog: Boolean) {
        val path = currentTargetPath() ?: return
        runCatching {
            McpConfigEditorSupport.saveConfig(path, editorArea.text)
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
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && !it.basePath.isNullOrBlank() }
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

    private fun createDetailLabel(label: String, value: String): JComponent {
        return JBLabel("<html><b>${htmlEscape(label)}:</b> ${htmlEscape(value)}</html>").apply {
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

    private fun normalizeEditorText(value: String): String {
        return value.replace("\r\n", "\n").trimEnd()
    }

    private fun mutedForeground(): java.awt.Color {
        return UIManager.getColor("Label.disabledForeground") ?: JBColor.GRAY
    }

    private fun Path.toDisplayString(): String {
        return toString().replace('\\', '/')
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun t(zh: String, en: String): String {
        return if (DisplayLanguage.fromStored(settings.displayLanguage) == DisplayLanguage.ZH_CN) zh else en
    }
}
