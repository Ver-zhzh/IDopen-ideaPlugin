package com.idopen.idopen.toolwindow

import com.idopen.idopen.agent.AgentSessionService
import com.idopen.idopen.agent.ApprovalPayload
import com.idopen.idopen.agent.ApprovalRequest
import com.idopen.idopen.agent.AttachmentContext
import com.idopen.idopen.agent.ChatSessionSummary
import com.idopen.idopen.agent.SessionEvent
import com.idopen.idopen.agent.SessionListener
import com.idopen.idopen.agent.TranscriptEntry
import com.idopen.idopen.settings.IDopenSettingsState
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBInsets
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

class IDopenToolWindowPanel(private val project: Project) {
    val component: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true)

    private val service = project.getService(AgentSessionService::class.java)
    private val transcriptPanel = JPanel()
    private val transcriptScrollPane = JBScrollPane(transcriptPanel)
    private val sessionSelector = JComboBox<ChatSessionSummary>()
    private val newSessionButton = JButton("+")
    private val composerActionButton = JButton("发送")
    private val inputArea = PromptTextArea(
        "输入你的需求，例如：解释当前类、修复这个错误、搜索某个调用链，或生成修改方案...",
        5,
        40,
    )
    private val includeCurrentFile = JBCheckBox("当前文件")
    private val includeSelection = JBCheckBox("当前选区")
    private val providerBadge = createPillLabel(Palette.PROVIDER_BG, Palette.PROVIDER_BORDER)
    private val endpointBadge = createPillLabel(Palette.MUTED_BG, Palette.MUTED_BORDER)
    private val modelBadge = createPillLabel(Palette.MUTED_BG, Palette.MUTED_BORDER)
    private val statusBadge = createPillLabel(Palette.STATUS_IDLE_BG, Palette.STATUS_IDLE_BORDER)
    private val trustModeCheckBox = JBCheckBox("信任模式")
    private val unlimitedUsageCheckBox = JBCheckBox("无限制使用")
    private val attachmentChips = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val messageAreas = linkedMapOf<String, JBTextArea>()
    private val collapsibleBodies = linkedMapOf<String, JComponent>()
    private val emptyState = createEmptyState()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private var currentSessionId: String = service.getCurrentSessionId()
    private var updatingSessionSelector = false
    @Suppress("unused")
    private val subscription = service.subscribe(SessionListener(::handleEvent))

    init {
        transcriptPanel.layout = BoxLayout(transcriptPanel, BoxLayout.Y_AXIS)
        transcriptPanel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        transcriptPanel.background = Palette.CANVAS

        transcriptScrollPane.border = BorderFactory.createEmptyBorder()
        transcriptScrollPane.viewport.background = Palette.CANVAS
        transcriptScrollPane.verticalScrollBar.unitIncrement = 18
        (transcriptScrollPane.verticalScrollBar as? JComponent)?.border = BorderFactory.createEmptyBorder()

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.margin = JBInsets(0, 0, 0, 0)
        inputArea.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        inputArea.background = Palette.COMPOSER_BG
        inputArea.foreground = JBColor.foreground()
        (inputArea.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
        registerSendShortcut()
        configureSessionSelector()

        val root = JBPanel<JBPanel<*>>(BorderLayout())
        root.background = Palette.CANVAS
        root.add(createStatusBar(), BorderLayout.NORTH)
        root.add(transcriptScrollPane, BorderLayout.CENTER)
        root.add(createComposer(), BorderLayout.SOUTH)

        component.setContent(root)
        refreshHeader()
        updateStatus("空闲")
        refreshComposerAction()
        refreshSessionSelector(service.getSessions(), service.getCurrentSessionId())
        renderCurrentTranscript()
    }

    private fun configureSessionSelector() {
        sessionSelector.preferredSize = Dimension(180, 28)
        sessionSelector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? ChatSessionSummary)?.title ?: "新对话"
                return component
            }
        }
        sessionSelector.addActionListener {
            if (updatingSessionSelector) return@addActionListener
            val summary = sessionSelector.selectedItem as? ChatSessionSummary ?: return@addActionListener
            service.selectSession(summary.id)
        }
        newSessionButton.margin = JBInsets(2, 8, 2, 8)
        newSessionButton.toolTipText = "新建会话"
        newSessionButton.addActionListener {
            service.createSession()
        }
        composerActionButton.preferredSize = Dimension(96, 34)
        composerActionButton.addActionListener {
            if (service.isRunning()) {
                service.stopCurrentRun()
            } else {
                submitMessage()
            }
        }
    }

    private fun refreshSessionSelector(summaries: List<ChatSessionSummary>, activeSessionId: String) {
        updatingSessionSelector = true
        sessionSelector.model = DefaultComboBoxModel(summaries.toTypedArray())
        sessionSelector.selectedItem = summaries.firstOrNull { it.id == activeSessionId }
        sessionSelector.isEnabled = !service.isRunning()
        newSessionButton.isEnabled = !service.isRunning()
        updatingSessionSelector = false
    }

    private fun renderCurrentTranscript() {
        messageAreas.clear()
        collapsibleBodies.clear()
        transcriptPanel.removeAll()
        val transcript = service.getTranscript()
        if (transcript.isEmpty()) {
            transcriptPanel.add(emptyState)
        } else {
            transcript.forEach(::renderEntry)
        }
        transcriptPanel.revalidate()
        transcriptPanel.repaint()
        scrollToBottom()
    }

    private fun createStatusBar(): JComponent {
        val title = JBLabel("IDopen")
        title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 1f)

        val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        topRow.isOpaque = false
        topRow.add(title)
        topRow.add(sessionSelector)
        topRow.add(newSessionButton)
        topRow.add(statusBadge)

        val chipsRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        chipsRow.isOpaque = false
        chipsRow.add(providerBadge)
        chipsRow.add(endpointBadge)
        chipsRow.add(modelBadge)

        val summary = JPanel()
        summary.layout = BoxLayout(summary, BoxLayout.Y_AXIS)
        summary.isOpaque = false
        summary.add(topRow)
        summary.add(Box.createRigidArea(Dimension(0, 6)))
        summary.add(chipsRow)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        right.isOpaque = false
        trustModeCheckBox.isOpaque = false
        trustModeCheckBox.isSelected = IDopenSettingsState.getInstance().trustMode
        trustModeCheckBox.toolTipText = "开启后自动批准所有命令和补丁请求。"
        unlimitedUsageCheckBox.isOpaque = false
        unlimitedUsageCheckBox.isSelected = IDopenSettingsState.getInstance().unlimitedUsage
        unlimitedUsageCheckBox.toolTipText = "开启后不再限制代理轮数和工具调用次数。"
        val settingsButton = JButton("设置")
        settingsButton.preferredSize = Dimension(86, 30)
        right.add(trustModeCheckBox)
        right.add(unlimitedUsageCheckBox)
        right.add(settingsButton)

        trustModeCheckBox.addActionListener {
            IDopenSettingsState.getInstance().trustMode = trustModeCheckBox.isSelected
        }
        unlimitedUsageCheckBox.addActionListener {
            IDopenSettingsState.getInstance().unlimitedUsage = unlimitedUsageCheckBox.isSelected
        }
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "IDopen")
            refreshHeader()
        }

        val bar = JPanel(BorderLayout())
        bar.background = Palette.SURFACE
        bar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.BORDER),
            BorderFactory.createEmptyBorder(10, 12, 10, 12),
        )
        bar.add(summary, BorderLayout.CENTER)
        bar.add(right, BorderLayout.EAST)
        return bar
    }

    private fun createComposer(): JComponent {
        val title = JBLabel("对话")
        title.font = title.font.deriveFont(Font.BOLD)
        val hint = JBLabel("Ctrl+Enter 发送；附带 IDE 上下文可以让结果更准。")
        hint.foreground = JBColor.GRAY

        val top = JPanel(BorderLayout())
        top.isOpaque = false
        top.add(title, BorderLayout.WEST)
        top.add(hint, BorderLayout.EAST)

        val editorWrap = JPanel(BorderLayout())
        editorWrap.background = Palette.COMPOSER_BG
        editorWrap.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.COMPOSER_BORDER),
            BorderFactory.createEmptyBorder(4, 4, 4, 4),
        )
        editorWrap.add(inputArea, BorderLayout.CENTER)

        val toggles = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        toggles.isOpaque = false
        includeCurrentFile.isOpaque = false
        includeSelection.isOpaque = false
        toggles.add(includeCurrentFile)
        toggles.add(includeSelection)
        includeCurrentFile.addActionListener { refreshAttachmentChips() }
        includeSelection.addActionListener { refreshAttachmentChips() }

        attachmentChips.isOpaque = false

        val footer = JPanel(BorderLayout())
        footer.isOpaque = false
        footer.add(toggles, BorderLayout.WEST)
        footer.add(composerActionButton, BorderLayout.EAST)

        val composer = JPanel()
        composer.layout = BoxLayout(composer, BoxLayout.Y_AXIS)
        composer.background = Palette.SURFACE
        composer.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12),
        )
        composer.add(top)
        composer.add(Box.createRigidArea(Dimension(0, 8)))
        composer.add(attachmentChips)
        composer.add(Box.createRigidArea(Dimension(0, 8)))
        composer.add(editorWrap)
        composer.add(Box.createRigidArea(Dimension(0, 10)))
        composer.add(footer)
        refreshAttachmentChips()
        return composer
    }

    private fun createEmptyState(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.background = Palette.EMPTY_BG
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.EMPTY_BORDER),
            BorderFactory.createEmptyBorder(14, 14, 14, 14),
        )

        val title = JBLabel("先告诉我你想做什么。")
        title.font = title.font.deriveFont(Font.BOLD)
        val body = JBLabel(
            "<html>先在<b>设置</b>里配置模型接口，然后试试：<i>“解释当前类”</i>、<i>“修复这个 bug”</i>、<i>“搜索这个 API 在哪里被调用”</i>。</html>",
        )
        body.foreground = JBColor.GRAY

        panel.add(title)
        panel.add(Box.createRigidArea(Dimension(0, 6)))
        panel.add(body)
        return panel
    }

    private fun registerSendShortcut() {
        val mask = if (System.getProperty("os.name").lowercase().contains("mac")) {
            InputEvent.META_DOWN_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK
        }
        val key = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask)
        inputArea.inputMap.put(key, "idopen.send")
        inputArea.actionMap.put("idopen.send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                submitMessage()
            }
        })
    }

    private fun submitMessage() {
        val text = inputArea.text.trim()
        if (text.isBlank()) return
        service.sendUserMessage(text, buildAttachments())
        inputArea.text = ""
    }

    private fun refreshHeader() {
        val settings = IDopenSettingsState.getInstance()
        providerBadge.text = "OpenAI-compatible"
        trustModeCheckBox.isSelected = settings.trustMode
        unlimitedUsageCheckBox.isSelected = settings.unlimitedUsage

        val endpoint = settings.baseUrl.trim()
        endpointBadge.text = if (endpoint.isBlank()) "未配置接口" else shortEndpoint(endpoint)
        endpointBadge.toolTipText = endpoint.ifBlank { "请先在设置中填写接口地址" }

        val model = settings.defaultModel.trim()
        modelBadge.text = if (model.isBlank()) "未选模型" else shorten(model, 18)
        modelBadge.toolTipText = model.ifBlank { "请先在设置中填写默认模型" }
    }

    private fun handleEvent(event: SessionEvent) {
        SwingUtilities.invokeLater {
            when (event) {
                is SessionEvent.SessionsChanged -> {
                    refreshSessionSelector(event.summaries, event.activeSessionId)
                    if (event.activeSessionId != currentSessionId) {
                        currentSessionId = event.activeSessionId
                        renderCurrentTranscript()
                    }
                }
                is SessionEvent.EntryAdded -> renderEntry(event.entry)
                is SessionEvent.MessageDelta -> messageAreas[event.messageId]?.text = event.snapshot
                is SessionEvent.RunStateChanged -> {
                    updateStatus(if (event.running) "运行中" else "空闲")
                    refreshComposerAction()
                    refreshHeader()
                }
                is SessionEvent.RunCompleted -> {
                    updateStatus("已完成")
                    refreshComposerAction()
                }
                is SessionEvent.RunFailed -> {
                    updateStatus("失败")
                    refreshComposerAction()
                }
                is SessionEvent.ToolRequested -> Unit
                is SessionEvent.ToolCompleted -> Unit
                is SessionEvent.ApprovalRequested -> Unit
            }
            transcriptPanel.revalidate()
            transcriptPanel.repaint()
            scrollToBottom()
        }
    }

    private fun updateStatus(status: String) {
        statusBadge.text = status
        when (status) {
            "运行中" -> setPillColors(statusBadge, Palette.STATUS_RUNNING_BG, Palette.STATUS_RUNNING_BORDER)
            "已完成" -> setPillColors(statusBadge, Palette.STATUS_DONE_BG, Palette.STATUS_DONE_BORDER)
            "失败" -> setPillColors(statusBadge, Palette.STATUS_ERROR_BG, Palette.STATUS_ERROR_BORDER)
            "已停止" -> setPillColors(statusBadge, Palette.STATUS_MUTED_BG, Palette.STATUS_MUTED_BORDER)
            else -> setPillColors(statusBadge, Palette.STATUS_IDLE_BG, Palette.STATUS_IDLE_BORDER)
        }
    }

    private fun refreshComposerAction() {
        val running = service.isRunning()
        composerActionButton.text = if (running) "停止" else "发送"
    }

    private fun renderEntry(entry: TranscriptEntry) {
        if (transcriptPanel.components.contains(emptyState)) {
            transcriptPanel.remove(emptyState)
        }

        when (entry) {
            is TranscriptEntry.User -> addMessageCard(
                id = entry.id,
                stage = "用户",
                title = "你",
                subtitle = null,
                text = entry.text,
                createdAt = entry.createdAt,
                background = Palette.USER_BG,
                accent = Palette.USER_ACCENT,
                collapsible = false,
                startCollapsed = false,
                alignRight = true,
                codeBlock = false,
            )

            is TranscriptEntry.Assistant -> addMessageCard(
                id = entry.id,
                stage = "助手",
                title = "IDopen",
                subtitle = null,
                text = entry.text,
                createdAt = entry.createdAt,
                background = Palette.ASSISTANT_BG,
                accent = Palette.ASSISTANT_ACCENT,
                collapsible = false,
                startCollapsed = false,
                alignRight = false,
                codeBlock = false,
            )

            is TranscriptEntry.ToolCall -> addMessageCard(
                id = entry.id,
                stage = "工具",
                title = entry.toolName,
                subtitle = "参数",
                text = entry.argumentsJson,
                createdAt = entry.createdAt,
                background = Palette.TOOL_BG,
                accent = Palette.TOOL_ACCENT,
                collapsible = true,
                startCollapsed = true,
                alignRight = false,
                codeBlock = true,
            )

            is TranscriptEntry.ToolResult -> addMessageCard(
                id = entry.id,
                stage = if (entry.success) "结果" else "结果/错误",
                title = entry.toolName,
                subtitle = if (entry.success) "已完成" else "失败",
                text = entry.output,
                createdAt = entry.createdAt,
                background = Palette.TOOL_BG,
                accent = Palette.TOOL_ACCENT,
                collapsible = true,
                startCollapsed = true,
                alignRight = false,
                codeBlock = true,
            )

            is TranscriptEntry.Error -> addMessageCard(
                id = entry.id,
                stage = "错误",
                title = "执行错误",
                subtitle = null,
                text = entry.message,
                createdAt = entry.createdAt,
                background = Palette.ERROR_BG,
                accent = Palette.ERROR_ACCENT,
                collapsible = false,
                startCollapsed = false,
                alignRight = false,
                codeBlock = false,
            )

            is TranscriptEntry.Context -> addMessageCard(
                id = entry.id,
                stage = "上下文",
                title = "IDE 上下文",
                subtitle = null,
                text = entry.summary,
                createdAt = entry.createdAt,
                background = Palette.SYSTEM_BG,
                accent = Palette.TOOL_ACCENT,
                collapsible = true,
                startCollapsed = false,
                alignRight = false,
                codeBlock = false,
            )

            is TranscriptEntry.System -> addMessageCard(
                id = entry.id,
                stage = "系统",
                title = "系统",
                subtitle = null,
                text = entry.message,
                createdAt = entry.createdAt,
                background = Palette.SYSTEM_BG,
                accent = Palette.SYSTEM_ACCENT,
                collapsible = true,
                startCollapsed = false,
                alignRight = false,
                codeBlock = false,
            )

            is TranscriptEntry.Approval -> addApprovalCard(entry)
        }
    }

    private fun addMessageCard(
        id: String,
        stage: String,
        title: String,
        subtitle: String?,
        text: String,
        createdAt: Instant,
        background: Color,
        accent: Color,
        collapsible: Boolean,
        startCollapsed: Boolean,
        alignRight: Boolean,
        codeBlock: Boolean,
    ) {
        if (messageAreas.containsKey(id)) return

        val card = JPanel(BorderLayout(0, 8))
        card.maximumSize = Dimension(if (alignRight) 500 else 620, Int.MAX_VALUE)
        card.background = background
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            ),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        left.isOpaque = false
        val stageLabel = createMiniTag(stage, accent)
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        left.add(stageLabel)
        left.add(titleLabel)
        if (!subtitle.isNullOrBlank()) {
            val subtitleLabel = JBLabel(subtitle)
            subtitleLabel.foreground = JBColor.GRAY
            left.add(subtitleLabel)
        }

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        right.isOpaque = false
        val timeLabel = JBLabel(formatTime(createdAt))
        timeLabel.foreground = JBColor.GRAY
        right.add(timeLabel)

        val pagination = if (codeBlock) PaginationState.create(text) else null
        val body = JBTextArea(pagination?.currentPageText() ?: text)
        body.isEditable = false
        body.lineWrap = !codeBlock
        body.wrapStyleWord = true
        body.background = background
        body.border = BorderFactory.createEmptyBorder()
        body.margin = JBInsets(0, 0, 0, 0)
        if (codeBlock) {
            body.font = Font(Font.MONOSPACED, Font.PLAIN, body.font.size)
            body.background = Palette.CODE_BG
            body.foreground = Palette.CODE_FG
            body.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.CODE_BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            )
        }

        val contentComponent: JComponent = if (codeBlock) {
            val codePanel = JPanel(BorderLayout(0, 6))
            codePanel.isOpaque = false
            val codeActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            codeActions.isOpaque = false
            pagination?.let { state ->
                val pageLabel = JBLabel(state.pageLabel())
                pageLabel.foreground = JBColor.GRAY
                val prevButton = JButton("上一页")
                val nextButton = JButton("下一页")
                prevButton.margin = JBInsets(2, 8, 2, 8)
                nextButton.margin = JBInsets(2, 8, 2, 8)
                val refreshPage = {
                    body.text = state.currentPageText()
                    pageLabel.text = state.pageLabel()
                    prevButton.isEnabled = state.hasPrevious()
                    nextButton.isEnabled = state.hasNext()
                }
                prevButton.addActionListener {
                    state.previous()
                    refreshPage()
                }
                nextButton.addActionListener {
                    state.next()
                    refreshPage()
                }
                refreshPage()
                codeActions.add(pageLabel)
                codeActions.add(prevButton)
                codeActions.add(nextButton)
            }
            val copyButton = JButton("复制")
            copyButton.margin = JBInsets(2, 8, 2, 8)
            copyButton.addActionListener { copyToClipboard(text) }
            codeActions.add(copyButton)
            codePanel.add(codeActions, BorderLayout.NORTH)
            codePanel.add(body, BorderLayout.CENTER)
            codePanel
        } else {
            body
        }

        if (collapsible) {
            val toggle = JButton(if (startCollapsed) "展开" else "收起")
            toggle.margin = JBInsets(2, 8, 2, 8)
            toggle.addActionListener {
                val expanded = !contentComponent.isVisible
                contentComponent.isVisible = expanded
                toggle.text = if (expanded) "收起" else "展开"
                transcriptPanel.revalidate()
                transcriptPanel.repaint()
            }
            contentComponent.isVisible = !startCollapsed
            right.add(toggle)
            collapsibleBodies[id] = body
        }

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        card.add(header, BorderLayout.NORTH)
        card.add(contentComponent, BorderLayout.CENTER)

        transcriptPanel.add(wrapCardRow(card, alignRight))
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 8)))
        messageAreas[id] = body
    }

    private fun addApprovalCard(entry: TranscriptEntry.Approval) {
        if (collapsibleBodies.containsKey(entry.request.id)) return

        val isCommand = entry.request.type == ApprovalRequest.Type.COMMAND
        val accent = if (isCommand) Palette.APPROVAL_COMMAND_ACCENT else Palette.APPROVAL_PATCH_ACCENT
        val background = if (isCommand) Palette.APPROVAL_COMMAND_BG else Palette.APPROVAL_PATCH_BG

        val card = JPanel(BorderLayout(0, 10))
        card.maximumSize = Dimension(620, Int.MAX_VALUE)
        card.background = background
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12),
            ),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        left.isOpaque = false
        left.add(createMiniTag(if (isCommand) "命令" else "补丁", accent))
        val titleLabel = JBLabel(entry.request.title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        left.add(titleLabel)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        right.isOpaque = false
        val statusLabel = createApprovalStatusLabel(entry.request.status)
        val timeLabel = JBLabel(formatTime(entry.createdAt))
        timeLabel.foreground = JBColor.GRAY
        val toggle = JButton("展开")
        toggle.margin = JBInsets(2, 8, 2, 8)
        right.add(statusLabel)
        right.add(timeLabel)
        right.add(toggle)

        val body = JBTextArea(renderApprovalText(entry.request))
        body.isEditable = false
        body.lineWrap = true
        body.wrapStyleWord = true
        body.background = background
        body.border = BorderFactory.createEmptyBorder()
        body.margin = JBInsets(0, 0, 0, 0)
        body.isVisible = false

        val riskBox = JPanel(BorderLayout())
        riskBox.isOpaque = true
        riskBox.background = if (isCommand) Palette.APPROVAL_COMMAND_WARN_BG else Palette.APPROVAL_PATCH_WARN_BG
        riskBox.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(if (isCommand) Palette.APPROVAL_COMMAND_WARN_BORDER else Palette.APPROVAL_PATCH_WARN_BORDER),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )
        val riskTitle = JBLabel(if (isCommand) "高风险操作" else "文件修改确认")
        riskTitle.font = riskTitle.font.deriveFont(Font.BOLD)
        val riskText = JBLabel(
            when (entry.request.type) {
                ApprovalRequest.Type.COMMAND -> "<html>即将在当前项目里执行 Shell 命令，请仔细确认命令内容和工作目录。</html>"
                ApprovalRequest.Type.PATCH -> "<html>即将把修改写入文件，会先显示 diff 预览，请确认目标文件和修改内容。</html>"
            },
        )
        riskText.foreground = JBColor.GRAY
        val riskInner = JPanel()
        riskInner.layout = BoxLayout(riskInner, BoxLayout.Y_AXIS)
        riskInner.isOpaque = false
        riskInner.add(riskTitle)
        riskInner.add(Box.createRigidArea(Dimension(0, 4)))
        riskInner.add(riskText)
        riskBox.add(riskInner, BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        actions.isOpaque = false
        val approve = JButton("批准")
        val reject = JButton("拒绝")
        actions.add(approve)
        actions.add(reject)

        fun refreshApprovalUi() {
            statusLabel.text = approvalStatusText(entry.request.status)
            statusLabel.isVisible = entry.request.status != ApprovalRequest.Status.PENDING
            setApprovalStatusColors(statusLabel, entry.request.status)

            val expanded = body.isVisible
            val pending = entry.request.status == ApprovalRequest.Status.PENDING
            actions.isVisible = expanded && pending
            approve.isVisible = pending
            reject.isVisible = pending
            approve.isEnabled = pending
            reject.isEnabled = pending
        }

        toggle.addActionListener {
            val expanded = !body.isVisible
            body.isVisible = expanded
            refreshApprovalUi()
            toggle.text = if (expanded) "收起" else "展开"
            transcriptPanel.revalidate()
            transcriptPanel.repaint()
        }

        approve.addActionListener {
            val accepted = when (val payload = entry.request.payload) {
                is ApprovalPayload.Patch -> previewPatchAndConfirm(payload)
                is ApprovalPayload.Command -> true
            }
            if (entry.request.type == ApprovalRequest.Type.PATCH) {
                service.approvePatch(entry.request.id, accepted)
            } else {
                service.approveCommand(entry.request.id, accepted)
            }
            entry.request.status = if (accepted) ApprovalRequest.Status.APPROVED else ApprovalRequest.Status.REJECTED
            refreshApprovalUi()
        }

        reject.addActionListener {
            if (entry.request.type == ApprovalRequest.Type.PATCH) {
                service.approvePatch(entry.request.id, false)
            } else {
                service.approveCommand(entry.request.id, false)
            }
            entry.request.status = ApprovalRequest.Status.REJECTED
            refreshApprovalUi()
        }

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        card.add(header, BorderLayout.NORTH)
        val center = JPanel()
        center.layout = BoxLayout(center, BoxLayout.Y_AXIS)
        center.isOpaque = false
        center.add(riskBox)
        center.add(Box.createRigidArea(Dimension(0, 8)))
        center.add(body)
        card.add(center, BorderLayout.CENTER)
        card.add(actions, BorderLayout.SOUTH)
        refreshApprovalUi()

        transcriptPanel.add(wrapCardRow(card, false))
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 8)))
        collapsibleBodies[entry.request.id] = body
    }

    private fun renderApprovalText(request: ApprovalRequest): String {
        return when (val payload = request.payload) {
            is ApprovalPayload.Command -> "命令：${payload.command}\n工作目录：${payload.workingDirectory}"
            is ApprovalPayload.Patch -> "文件：${payload.filePath}\n说明：${payload.explanation}"
        }
    }

    private fun approvalStatusText(status: ApprovalRequest.Status): String {
        return when (status) {
            ApprovalRequest.Status.PENDING -> ""
            ApprovalRequest.Status.APPROVED -> "已批准"
            ApprovalRequest.Status.REJECTED -> "已拒绝"
        }
    }

    private fun createApprovalStatusLabel(status: ApprovalRequest.Status): JBLabel {
        return createPillLabel(Palette.STATUS_DONE_BG, Palette.STATUS_DONE_BORDER).apply {
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            text = approvalStatusText(status)
            isVisible = status != ApprovalRequest.Status.PENDING
        }
    }

    private fun setApprovalStatusColors(label: JBLabel, status: ApprovalRequest.Status) {
        when (status) {
            ApprovalRequest.Status.PENDING -> setPillColors(label, Palette.STATUS_IDLE_BG, Palette.STATUS_IDLE_BORDER)
            ApprovalRequest.Status.APPROVED -> setPillColors(label, Palette.STATUS_DONE_BG, Palette.STATUS_DONE_BORDER)
            ApprovalRequest.Status.REJECTED -> setPillColors(label, Palette.STATUS_ERROR_BG, Palette.STATUS_ERROR_BORDER)
        }
    }

    private fun previewPatchAndConfirm(payload: ApprovalPayload.Patch): Boolean {
        val contentFactory = DiffContentFactory.getInstance()
        val before = contentFactory.create(payload.beforeText)
        val after = contentFactory.create(payload.afterText)
        val request = SimpleDiffRequest(
            "补丁预览：${payload.filePath}",
            before,
            after,
            "当前内容",
            "建议修改",
        )
        DiffManager.getInstance().showDiff(project, request)
        return Messages.showYesNoDialog(
            project,
            "确认将修改应用到 ${payload.filePath} 吗？",
            "IDopen 补丁审批",
            null,
        ) == Messages.YES
    }

    private fun buildAttachments(): List<AttachmentContext> {
        val attachments = mutableListOf<AttachmentContext>()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (includeCurrentFile.isSelected && editor != null) {
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            if (file != null) {
                val relative = relativeProjectPath(file)
                attachments += AttachmentContext(
                    kind = com.idopen.idopen.agent.AttachmentKind.CURRENT_FILE,
                    label = "当前文件",
                    reference = "当前文件：$relative",
                    path = relative,
                    resolvedContent = editor.document.text.take(12_000),
                    content = editor.document.text.take(12_000),
                )
            }
        }
        if (includeSelection.isSelected && editor != null) {
            val selection = editor.selectionModel.selectedText
            if (!selection.isNullOrBlank()) {
                val file = FileDocumentManager.getInstance().getFile(editor.document)
                val relative = file?.let(::relativeProjectPath) ?: "<current editor>"
                val startOffset = editor.selectionModel.selectionStart
                val endOffset = editor.selectionModel.selectionEnd
                val startLine = editor.document.getLineNumber(startOffset) + 1
                val endLine = editor.document.getLineNumber((endOffset - 1).coerceAtLeast(startOffset)) + 1
                attachments += AttachmentContext(
                    kind = com.idopen.idopen.agent.AttachmentKind.CURRENT_SELECTION,
                    label = "当前选区",
                    reference = "当前选区：$relative 第 $startLine-$endLine 行，共 ${selection.length} 个字符",
                    path = relative,
                    startLine = startLine,
                    endLine = endLine,
                    resolvedContent = selection,
                    content = selection,
                )
            }
        }
        return attachments
    }

    private fun formatTime(value: Instant): String = timeFormatter.format(value)

    private fun shortEndpoint(baseUrl: String): String {
        val cleaned = baseUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return shorten(cleaned, 18)
    }

    private fun shorten(value: String, maxLength: Int): String {
        return if (value.length <= maxLength) value else value.take(maxLength - 3) + "..."
    }

    private fun scrollToBottom() {
        val bar = transcriptScrollPane.verticalScrollBar
        bar.value = bar.maximum
    }

    private fun refreshAttachmentChips() {
        attachmentChips.removeAll()
        currentAttachmentChipLabels().forEach { label ->
            attachmentChips.add(createAttachmentChip(label))
        }
        attachmentChips.revalidate()
        attachmentChips.repaint()
    }

    private fun currentAttachmentChipLabels(): List<String> {
        val labels = mutableListOf<String>()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }

        if (includeCurrentFile.isSelected && file != null) {
            val relative = relativeProjectPath(file)
            labels += "文件：${shorten(relative, 18)}"
        }
        if (includeSelection.isSelected) {
            val selection = editor?.selectionModel?.selectedText
            labels += if (!selection.isNullOrBlank()) {
                "选区：${selection.length} 字符"
            } else {
                "选区：未选择"
            }
        }
        if (labels.isEmpty()) {
            labels += "未附带上下文"
        }
        return labels
    }

    private fun relativeProjectPath(file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val normalizedBase = java.nio.file.Paths.get(basePath).toAbsolutePath().normalize()
        val normalizedFile = java.nio.file.Paths.get(file.path).toAbsolutePath().normalize()
        return if (normalizedFile.startsWith(normalizedBase)) {
            normalizedBase.relativize(normalizedFile).toString().replace('\\', '/')
        } else {
            file.path
        }
    }

    private fun createAttachmentChip(text: String): JComponent {
        val label = JBLabel(text)
        label.isOpaque = true
        label.background = Palette.CHIP_BG
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.CHIP_BORDER),
            BorderFactory.createEmptyBorder(3, 8, 3, 8),
        )
        return label
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }.recoverCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    private fun wrapCardRow(card: JComponent, alignRight: Boolean): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        if (alignRight) {
            row.add(Box.createHorizontalGlue())
            row.add(card)
        } else {
            row.add(card)
            row.add(Box.createHorizontalGlue())
        }
        return row
    }

    private fun createPillLabel(background: Color, border: Color): JBLabel {
        val label = JBLabel()
        label.isOpaque = true
        setPillColors(label, background, border)
        return label
    }

    private fun setPillColors(label: JBLabel, background: Color, border: Color) {
        label.background = background
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border),
            BorderFactory.createEmptyBorder(3, 8, 3, 8),
        )
    }

    private fun createMiniTag(text: String, color: Color): JBLabel {
        val label = JBLabel(text)
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 1f)
        label.foreground = color
        return label
    }

    private class PaginationState private constructor(
        private val pages: List<String>,
    ) {
        private var index = 0

        fun currentPageText(): String = pages[index]

        fun pageLabel(): String = "${index + 1}/${pages.size}"

        fun hasPrevious(): Boolean = index > 0

        fun hasNext(): Boolean = index < pages.lastIndex

        fun previous() {
            if (hasPrevious()) index -= 1
        }

        fun next() {
            if (hasNext()) index += 1
        }

        companion object {
            private const val PAGE_LINES = 80

            fun create(text: String): PaginationState? {
                val lines = text.lines()
                if (lines.size <= PAGE_LINES) return null
                val pages = lines.chunked(PAGE_LINES).map { it.joinToString("\n") }
                return PaginationState(pages)
            }
        }
    }

    private class PromptTextArea(
        private val placeholder: String,
        rows: Int,
        columns: Int,
    ) : JBTextArea(rows, columns) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isNotEmpty() || hasFocus()) return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = JBColor.GRAY
            g2.font = font.deriveFont(Font.PLAIN)
            val fm = g2.fontMetrics
            g2.drawString(placeholder, insets.left + 2, insets.top + fm.ascent + 2)
            g2.dispose()
        }
    }

    private object Palette {
        val CANVAS = JBColor(Color(245, 246, 248), Color(33, 36, 41))
        val SURFACE = JBColor(Color(255, 255, 255), Color(40, 43, 48))
        val BORDER = JBColor(Color(219, 223, 230), Color(76, 81, 89))

        val PROVIDER_BG = JBColor(Color(234, 243, 255), Color(36, 56, 84))
        val PROVIDER_BORDER = JBColor(Color(175, 199, 236), Color(76, 110, 156))

        val MUTED_BG = JBColor(Color(243, 244, 246), Color(55, 59, 66))
        val MUTED_BORDER = JBColor(Color(206, 211, 218), Color(83, 88, 98))

        val STATUS_IDLE_BG = JBColor(Color(242, 243, 245), Color(55, 58, 64))
        val STATUS_IDLE_BORDER = JBColor(Color(205, 208, 214), Color(85, 89, 97))
        val STATUS_RUNNING_BG = JBColor(Color(226, 245, 233), Color(45, 95, 67))
        val STATUS_RUNNING_BORDER = JBColor(Color(162, 212, 182), Color(71, 126, 93))
        val STATUS_DONE_BG = JBColor(Color(229, 241, 255), Color(41, 75, 108))
        val STATUS_DONE_BORDER = JBColor(Color(166, 198, 237), Color(69, 109, 151))
        val STATUS_ERROR_BG = JBColor(Color(255, 232, 232), Color(94, 45, 45))
        val STATUS_ERROR_BORDER = JBColor(Color(231, 169, 169), Color(126, 67, 67))
        val STATUS_MUTED_BG = JBColor(Color(243, 239, 230), Color(76, 69, 49))
        val STATUS_MUTED_BORDER = JBColor(Color(224, 209, 170), Color(109, 99, 70))

        val EMPTY_BG = JBColor(Color(251, 248, 239), Color(54, 50, 42))
        val EMPTY_BORDER = JBColor(Color(235, 221, 180), Color(91, 84, 66))

        val USER_BG = JBColor(Color(238, 245, 255), Color(37, 49, 72))
        val USER_ACCENT = JBColor(Color(82, 126, 213), Color(105, 155, 255))

        val ASSISTANT_BG = JBColor(Color(255, 255, 255), Color(42, 45, 52))
        val ASSISTANT_ACCENT = JBColor(Color(69, 152, 103), Color(95, 194, 136))

        val TOOL_BG = JBColor(Color(247, 248, 250), Color(46, 49, 56))
        val TOOL_ACCENT = JBColor(Color(124, 130, 144), Color(151, 156, 167))

        val SYSTEM_BG = JBColor(Color(249, 247, 241), Color(55, 52, 44))
        val SYSTEM_ACCENT = JBColor(Color(190, 146, 49), Color(216, 174, 88))

        val ERROR_BG = JBColor(Color(255, 236, 236), Color(82, 38, 38))
        val ERROR_ACCENT = JBColor(Color(210, 79, 79), Color(236, 112, 112))

        val CODE_BG = JBColor(Color(249, 250, 252), Color(28, 31, 36))
        val CODE_BORDER = JBColor(Color(214, 220, 228), Color(68, 73, 82))
        val CODE_FG = JBColor(Color(48, 56, 70), Color(214, 220, 229))

        val APPROVAL_COMMAND_BG = JBColor(Color(255, 244, 239), Color(70, 44, 39))
        val APPROVAL_COMMAND_ACCENT = JBColor(Color(216, 97, 56), Color(242, 132, 92))
        val APPROVAL_COMMAND_WARN_BG = JBColor(Color(255, 231, 221), Color(92, 57, 49))
        val APPROVAL_COMMAND_WARN_BORDER = JBColor(Color(228, 140, 111), Color(157, 104, 87))

        val APPROVAL_PATCH_BG = JBColor(Color(255, 248, 236), Color(67, 55, 35))
        val APPROVAL_PATCH_ACCENT = JBColor(Color(214, 145, 49), Color(240, 177, 93))
        val APPROVAL_PATCH_WARN_BG = JBColor(Color(255, 239, 210), Color(86, 69, 39))
        val APPROVAL_PATCH_WARN_BORDER = JBColor(Color(229, 188, 109), Color(144, 117, 62))

        val CHIP_BG = JBColor(Color(241, 245, 250), Color(53, 58, 66))
        val CHIP_BORDER = JBColor(Color(203, 212, 224), Color(84, 90, 101))

        val COMPOSER_BG = JBColor(Color(255, 255, 255), Color(35, 38, 44))
        val COMPOSER_BORDER = JBColor(Color(196, 204, 215), Color(86, 92, 102))
    }
}
