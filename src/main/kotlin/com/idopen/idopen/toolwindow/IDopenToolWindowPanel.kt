package com.idopen.idopen.toolwindow

import com.idopen.idopen.agent.AgentSessionService
import com.idopen.idopen.agent.ApprovalPayload
import com.idopen.idopen.agent.ApprovalRequest
import com.idopen.idopen.agent.AssistantOutputPart
import com.idopen.idopen.agent.AttachmentContext
import com.idopen.idopen.agent.ChatSessionSummary
import com.idopen.idopen.agent.ChatSessionSnapshot
import com.idopen.idopen.agent.SessionStep
import com.idopen.idopen.agent.SessionEvent
import com.idopen.idopen.agent.SessionListener
import com.idopen.idopen.agent.SessionStepPart
import com.idopen.idopen.agent.ToolInvocationState
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
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
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
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
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
    private val composerFrame = JPanel(BorderLayout())
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
    private val messageAreas = linkedMapOf<String, (String) -> Unit>()
    private val collapsibleBodies = linkedMapOf<String, JComponent>()
    private val collapsedState = mutableMapOf<String, Boolean>()
    private val emptyState = createEmptyState()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private var currentSessionId: String = service.getCurrentSessionId()
    private var currentSnapshot: ChatSessionSnapshot = service.getCurrentSessionSnapshot()
    private var updatingSessionSelector = false
    private var lastRenderedRoundId: String? = null
    private var renderedRoundCount: Int = 0
    @Suppress("unused")
    private val subscription = service.subscribe(SessionListener(::handleEvent))

    init {
        transcriptPanel.layout = BoxLayout(transcriptPanel, BoxLayout.Y_AXIS)
        transcriptPanel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        transcriptPanel.background = Palette.CANVAS

        transcriptScrollPane.border = BorderFactory.createEmptyBorder()
        transcriptScrollPane.viewport.background = Palette.CANVAS
        transcriptScrollPane.verticalScrollBar.unitIncrement = 18
        transcriptScrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
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
        renderCurrentSnapshot()
    }

    private fun configureSessionSelector() {
        sessionSelector.preferredSize = Dimension(180, 28)
        sessionSelector.isOpaque = false
        sessionSelector.border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
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
        newSessionButton.text = "+"
        newSessionButton.margin = JBInsets(2, 10, 2, 10)
        newSessionButton.isOpaque = false
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

    private fun renderCurrentSnapshot(snapshot: ChatSessionSnapshot = currentSnapshot) {
        currentSnapshot = snapshot
        messageAreas.clear()
        collapsibleBodies.clear()
        lastRenderedRoundId = null
        renderedRoundCount = 0
        transcriptPanel.removeAll()
        if (snapshot.transcript.isEmpty()) {
            transcriptPanel.add(emptyState)
        } else {
            snapshot.transcript
                .filter { it.roundId == null }
                .forEach(::renderStandaloneEntry)

            if (snapshot.steps.isEmpty()) {
                snapshot.transcript
                    .filter { it.roundId != null }
                    .forEach(::renderTranscriptEntry)
            } else {
                snapshot.steps.forEach(::renderStep)
            }
        }
        transcriptPanel.revalidate()
        transcriptPanel.repaint()
        scrollToBottom()
    }

    private fun createStatusBar(): JComponent {
        val title = JBLabel("IDopen")
        title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 1f)

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        titleRow.isOpaque = false
        titleRow.add(title)
        titleRow.add(statusBadge)
        val sessionWrap = JPanel(BorderLayout(6, 0))
        sessionWrap.background = Palette.HEADER_FIELD_BG
        sessionWrap.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.HEADER_FIELD_BORDER),
            BorderFactory.createEmptyBorder(2, 6, 2, 4),
        )
        sessionWrap.add(sessionSelector, BorderLayout.CENTER)
        sessionWrap.add(newSessionButton, BorderLayout.EAST)
        val left = JPanel()
        left.layout = BoxLayout(left, BoxLayout.Y_AXIS)
        left.isOpaque = false
        left.add(titleRow)
        left.add(Box.createRigidArea(Dimension(0, 6)))
        left.add(sessionWrap)

        val chipsRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        chipsRow.isOpaque = false
        chipsRow.add(providerBadge)
        chipsRow.add(endpointBadge)
        chipsRow.add(modelBadge)

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

        val rightGroup = JPanel()
        rightGroup.layout = BoxLayout(rightGroup, BoxLayout.Y_AXIS)
        rightGroup.isOpaque = false
        chipsRow.alignmentX = Component.RIGHT_ALIGNMENT
        right.alignmentX = Component.RIGHT_ALIGNMENT
        rightGroup.add(chipsRow)
        rightGroup.add(Box.createRigidArea(Dimension(0, 6)))
        rightGroup.add(right)

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
        bar.add(left, BorderLayout.WEST)
        bar.add(rightGroup, BorderLayout.EAST)
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

        composerFrame.removeAll()
        composerFrame.background = Palette.COMPOSER_BG
        composerFrame.add(inputArea, BorderLayout.CENTER)
        composerFrame.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.COMPOSER_BORDER),
            BorderFactory.createEmptyBorder(4, 4, 4, 4),
        )
        inputArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = refreshComposerChrome()

            override fun focusLost(e: FocusEvent) = refreshComposerChrome()
        })
        composerActionButton.isOpaque = true
        composerActionButton.foreground = JBColor(Color(255, 255, 255), Color(255, 255, 255))
        composerActionButton.background = Palette.ACTION_SEND_BG
        composerActionButton.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.ACTION_SEND_BORDER),
            BorderFactory.createEmptyBorder(4, 14, 4, 14),
        )

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
        refreshComposerChrome()

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
        composer.add(composerFrame)
        composer.add(Box.createRigidArea(Dimension(0, 10)))
        composer.add(footer)
        refreshAttachmentChips()
        refreshComposerChrome()
        return composer
    }

    private fun refreshComposerChrome() {
        val running = service.isRunning()
        val focused = inputArea.hasFocus()
        composerActionButton.text = if (running) "停止" else "发送"
        composerActionButton.isOpaque = true
        composerActionButton.foreground = JBColor(Color(255, 255, 255), Color(255, 255, 255))
        composerFrame.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                if (focused) Palette.COMPOSER_ACTIVE_BORDER else Palette.COMPOSER_BORDER,
                if (focused) 2 else 1,
            ),
            BorderFactory.createEmptyBorder(4, 4, 4, 4),
        )
        composerActionButton.background = if (running) Palette.ACTION_STOP_BG else Palette.ACTION_SEND_BG
        composerActionButton.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(if (running) Palette.ACTION_STOP_BORDER else Palette.ACTION_SEND_BORDER),
            BorderFactory.createEmptyBorder(4, 14, 4, 14),
        )
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
                        currentSnapshot = service.getCurrentSessionSnapshot()
                        renderCurrentSnapshot()
                    }
                }
                is SessionEvent.SessionSnapshotChanged -> {
                    if (event.snapshot.sessionId == currentSessionId) {
                        currentSnapshot = event.snapshot
                        renderCurrentSnapshot(event.snapshot)
                    }
                }
                is SessionEvent.EntryAdded -> Unit
                is SessionEvent.EntryUpdated -> Unit
                is SessionEvent.MessageDelta -> Unit
                is SessionEvent.RunStateChanged -> {
                    updateStatus(if (event.running) "运行中" else "空闲")
                    refreshComposerAction()
                    refreshComposerChrome()
                    refreshHeader()
                }
                is SessionEvent.RunCompleted -> {
                    updateStatus("已完成")
                    refreshComposerAction()
                    refreshComposerChrome()
                }
                is SessionEvent.RunFailed -> {
                    updateStatus("失败")
                    refreshComposerAction()
                    refreshComposerChrome()
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

    private fun renderStandaloneEntry(entry: TranscriptEntry) {
        when (entry) {
            is TranscriptEntry.System -> addSystemBanner(
                id = entry.id,
                stage = "系统",
                title = "系统",
                subtitle = null,
                text = entry.message,
                createdAt = entry.createdAt,
                background = Palette.SYSTEM_BG,
                accent = Palette.SYSTEM_ACCENT,
                collapsible = true,
                startCollapsed = collapsedState[entry.id] ?: false,
                alignRight = false,
                codeBlock = false,
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

            else -> renderTranscriptEntry(entry)
        }
    }

    private fun renderStep(step: SessionStep) {
        maybeRenderRoundSeparator(step.roundId)
        addStepRow(
            id = "step-${step.roundId}-${step.stepIndex ?: "pending"}",
            text = buildString {
                append(step.title)
                step.summary?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(it)
                }
            },
            createdAt = step.startedAt ?: step.finishedAt ?: currentSnapshot.updatedAt,
            accent = when (step.status) {
                com.idopen.idopen.agent.SessionStepStatus.RUNNING -> Palette.TOOL_ACCENT
                com.idopen.idopen.agent.SessionStepStatus.COMPLETED -> Palette.ASSISTANT_ACCENT
                com.idopen.idopen.agent.SessionStepStatus.FAILED -> Palette.ERROR_ACCENT
            },
        )

        val parts = step.parts
        var index = 0
        while (index < parts.size) {
            when (val part = parts[index]) {
                is SessionStepPart.ToolCall -> {
                    val next = parts.getOrNull(index + 1) as? SessionStepPart.ToolResult
                    val resultPart = next?.takeIf { it.callId == part.callId }
                    renderToolPart(step, part, resultPart, index)
                    index += if (resultPart != null) 2 else 1
                }

                is SessionStepPart.ApprovalRequestPart -> {
                    val next = parts.getOrNull(index + 1) as? SessionStepPart.ApprovalDecision
                    val decisionPart = next?.takeIf { it.requestId == part.requestId }
                    renderApprovalPart(step, part, decisionPart, index)
                    index += if (decisionPart != null) 2 else 1
                }

                else -> {
                    renderStepPart(step, part, index)
                    index += 1
                }
            }
        }
    }

    private fun renderStepPart(
        step: SessionStep,
        part: SessionStepPart,
        index: Int,
    ) {
        val id = "step-${step.roundId}-${step.stepIndex ?: "pending"}-part-$index"
        when (part) {
            is SessionStepPart.Context -> addMessageCard(
                id = id,
                stage = "上下文",
                title = "IDE 上下文",
                subtitle = null,
                text = part.summary,
                createdAt = part.createdAt,
                background = Palette.SYSTEM_BG,
                accent = Palette.TOOL_ACCENT,
                collapsible = true,
                startCollapsed = collapsedState[id] ?: false,
                alignRight = false,
                codeBlock = false,
            )

            is SessionStepPart.User -> addMessageCard(
                id = id,
                stage = "用户",
                title = "你",
                subtitle = null,
                text = part.text,
                createdAt = part.createdAt,
                background = Palette.USER_BG,
                accent = Palette.USER_ACCENT,
                collapsible = false,
                startCollapsed = false,
                alignRight = true,
                codeBlock = false,
            )

            is SessionStepPart.AssistantResponse -> addMessageCard(
                id = id,
                stage = "助手",
                title = "IDopen",
                subtitle = null,
                text = part.text,
                createdAt = part.createdAt,
                background = Palette.ASSISTANT_BG,
                accent = Palette.ASSISTANT_ACCENT,
                collapsible = false,
                startCollapsed = false,
                alignRight = false,
                codeBlock = false,
                outputParts = part.outputParts,
            )

            is SessionStepPart.Error -> addMessageCard(
                id = id,
                stage = "错误",
                title = "执行错误",
                subtitle = null,
                text = buildString {
                    append(part.message)
                    part.recoveryHint?.takeIf { it.isNotBlank() }?.let {
                        append("\n\nRecovery hint\n")
                        append(it)
                    }
                },
                createdAt = part.createdAt,
                background = Palette.ERROR_BG,
                accent = Palette.ERROR_ACCENT,
                collapsible = false,
                startCollapsed = false,
                alignRight = false,
                codeBlock = false,
            )

            is SessionStepPart.System -> addSystemBanner(
                id = id,
                stage = "系统",
                title = "系统",
                subtitle = null,
                text = part.message,
                createdAt = part.createdAt,
                background = Palette.SYSTEM_BG,
                accent = Palette.SYSTEM_ACCENT,
                collapsible = true,
                startCollapsed = collapsedState[id] ?: false,
                alignRight = false,
                codeBlock = false,
            )

            else -> Unit
        }
    }

    private fun renderToolPart(
        step: SessionStep,
        callPart: SessionStepPart.ToolCall,
        resultPart: SessionStepPart.ToolResult?,
        index: Int,
    ) {
        val state = resultPart?.state ?: ToolInvocationState.PENDING
        val accent = if (state == ToolInvocationState.ERROR) Palette.ERROR_ACCENT else Palette.TOOL_ACCENT
        val subtitle = buildString {
            append(
                when (state) {
                    ToolInvocationState.PENDING -> "等待中"
                    ToolInvocationState.RUNNING -> "执行中"
                    ToolInvocationState.COMPLETED -> "已完成"
                    ToolInvocationState.ERROR -> "失败"
                },
            )
            callPart.title?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        }
        val details = buildString {
            if (callPart.metadata.isNotEmpty()) {
                appendLine("metadata")
                callPart.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                    appendLine("$key: $value")
                }
                appendLine()
            }
            appendLine("arguments")
            appendLine(callPart.argumentsJson)
            resultPart?.output?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(if (state == ToolInvocationState.ERROR) "error" else "result")
                appendLine(it)
            }
            resultPart?.recoveryHint?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("recovery hint")
                appendLine(it)
            }
        }.trim()
        addToolEventRow(
            id = "step-${step.roundId}-${step.stepIndex ?: "pending"}-tool-$index",
            stage = "工具",
            title = callPart.toolName,
            subtitle = subtitle,
            text = details,
            createdAt = callPart.createdAt,
            accent = accent,
        )
    }

    private fun renderApprovalPart(
        step: SessionStep,
        requestPart: SessionStepPart.ApprovalRequestPart,
        decisionPart: SessionStepPart.ApprovalDecision?,
        index: Int,
    ) {
        addApprovalCard(
            TranscriptEntry.Approval(
                id = "step-${step.roundId}-${step.stepIndex ?: "pending"}-approval-$index",
                request = ApprovalRequest(
                    id = requestPart.requestId,
                    type = requestPart.type,
                    title = requestPart.title,
                    payload = requestPart.payload,
                    status = decisionPart?.status ?: ApprovalRequest.Status.PENDING,
                ),
                createdAt = requestPart.createdAt,
                roundId = step.roundId,
            ),
        )
    }

    private fun renderEntry(entry: TranscriptEntry) {
        if (transcriptPanel.components.contains(emptyState)) {
            transcriptPanel.remove(emptyState)
        }
        maybeRenderRoundSeparator(entry)

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

            is TranscriptEntry.ToolCall -> addToolEventRow(
                id = entry.id,
                stage = "工具",
                title = entry.toolName,
                subtitle = "参数",
                text = entry.argumentsJson,
                createdAt = entry.createdAt,
                accent = Palette.TOOL_ACCENT,
            )

            is TranscriptEntry.ToolResult -> addToolEventRow(
                id = entry.id,
                stage = if (entry.success) "结果" else "结果/错误",
                title = entry.toolName,
                subtitle = if (entry.success) "已完成" else "失败",
                text = entry.output,
                createdAt = entry.createdAt,
                accent = if (entry.success) Palette.TOOL_ACCENT else Palette.ERROR_ACCENT,
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

            is TranscriptEntry.System -> addSystemBanner(
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

            is TranscriptEntry.StepStart -> renderTranscriptEntry(entry)

            is TranscriptEntry.StepFinish -> renderTranscriptEntry(entry)

            is TranscriptEntry.Approval -> addApprovalCard(entry)

            else -> renderTranscriptEntry(entry)
        }
    }

    private fun renderTranscriptEntry(entry: TranscriptEntry) {
        if (transcriptPanel.components.contains(emptyState)) {
            transcriptPanel.remove(emptyState)
        }
        maybeRenderRoundSeparator(entry)

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

            is TranscriptEntry.ToolCall -> addToolEventRow(
                id = entry.id,
                stage = "工具",
                title = entry.toolName,
                subtitle = "参数",
                text = entry.argumentsJson,
                createdAt = entry.createdAt,
                accent = Palette.TOOL_ACCENT,
            )

            is TranscriptEntry.ToolResult -> addToolEventRow(
                id = entry.id,
                stage = if (entry.success) "结果" else "结果/错误",
                title = entry.toolName,
                subtitle = if (entry.success) "已完成" else "失败",
                text = entry.output,
                createdAt = entry.createdAt,
                accent = if (entry.success) Palette.TOOL_ACCENT else Palette.ERROR_ACCENT,
            )

            is TranscriptEntry.Approval -> addApprovalCard(entry)

            is TranscriptEntry.StepStart -> addStepRow(
                id = entry.id,
                text = "步骤 ${entry.stepIndex} 开始",
                createdAt = entry.createdAt,
                accent = Palette.TOOL_ACCENT,
            )

            is TranscriptEntry.StepFinish -> addStepRow(
                id = entry.id,
                text = buildString {
                    append("步骤 ${entry.stepIndex} ")
                    append(if (entry.success) "完成" else "结束")
                    append(" · ")
                    append(
                        when (entry.reason) {
                            "final" -> "直接答复"
                            "tool-loop" -> "工具循环"
                            "tool-disabled" -> "工具未启用"
                            "tool-limit" -> "达到工具上限"
                            else -> entry.reason
                        },
                    )
                    if (entry.toolCalls > 0) {
                        append(" · ${entry.toolCalls} 个工具")
                    }
                },
                createdAt = entry.createdAt,
                accent = if (entry.success) Palette.TOOL_ACCENT else Palette.ERROR_ACCENT,
            )

            is TranscriptEntry.ToolInvocation -> addToolInvocationRow(entry)

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

            is TranscriptEntry.System -> addSystemBanner(
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
        }
    }

    private fun addStepRow(
        id: String,
        text: String,
        createdAt: Instant,
        accent: Color,
    ) {
        if (messageAreas.containsKey(id)) return

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.isOpaque = false
        row.maximumSize = Dimension(Int.MAX_VALUE, 22)
        row.alignmentX = Component.LEFT_ALIGNMENT

        val dot = JPanel()
        dot.background = accent
        dot.preferredSize = Dimension(6, 6)
        dot.maximumSize = Dimension(6, 6)
        dot.minimumSize = Dimension(6, 6)
        dot.border = BorderFactory.createEmptyBorder()

        val label = JBLabel(text)
        label.foreground = JBColor.GRAY
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 1f)

        val timeLabel = JBLabel(formatTime(createdAt))
        timeLabel.foreground = JBColor.GRAY

        row.add(dot)
        row.add(label)
        row.add(timeLabel)

        transcriptPanel.add(row)
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 4)))
        messageAreas[id] = {}
    }

    private fun maybeRenderRoundSeparator(entry: TranscriptEntry) {
        maybeRenderRoundSeparator(entry.roundId)
    }

    private fun maybeRenderRoundSeparator(roundId: String?) {
        roundId ?: return
        if (roundId == lastRenderedRoundId) return
        lastRenderedRoundId = roundId
        renderedRoundCount += 1

        if (transcriptPanel.componentCount > 0) {
            transcriptPanel.add(Box.createRigidArea(Dimension(0, 8)))
        }

        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.maximumSize = Dimension(Int.MAX_VALUE, 20)
        row.alignmentX = Component.LEFT_ALIGNMENT

        val leftLine = JPanel()
        leftLine.background = Palette.BORDER
        leftLine.preferredSize = Dimension(28, 1)
        leftLine.maximumSize = Dimension(Int.MAX_VALUE, 1)

        val rightLine = JPanel()
        rightLine.background = Palette.BORDER
        rightLine.preferredSize = Dimension(28, 1)
        rightLine.maximumSize = Dimension(Int.MAX_VALUE, 1)

        val label = JBLabel("第 $renderedRoundCount 轮")
        label.foreground = JBColor.GRAY
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 1f)

        val leftWrap = JPanel(BorderLayout())
        leftWrap.isOpaque = false
        leftWrap.border = BorderFactory.createEmptyBorder(9, 0, 0, 0)
        leftWrap.add(leftLine, BorderLayout.CENTER)

        val rightWrap = JPanel(BorderLayout())
        rightWrap.isOpaque = false
        rightWrap.border = BorderFactory.createEmptyBorder(9, 0, 0, 0)
        rightWrap.add(rightLine, BorderLayout.CENTER)

        row.add(leftWrap)
        row.add(Box.createRigidArea(Dimension(8, 0)))
        row.add(label)
        row.add(Box.createRigidArea(Dimension(8, 0)))
        row.add(rightWrap)
        row.add(Box.createHorizontalGlue())

        transcriptPanel.add(row)
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 8)))
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
        outputParts: List<AssistantOutputPart> = emptyList(),
    ) {
        if (messageAreas.containsKey(id)) return

        val bubbleStyle = alignRight || title == "IDopen"
        val card = if (bubbleStyle) {
            BubblePanel(
                backgroundColor = background,
                borderColor = if (alignRight) Palette.USER_BUBBLE_BORDER else Palette.ASSISTANT_BUBBLE_BORDER,
                arc = 18,
            )
        } else {
            JPanel(BorderLayout(0, 6)).apply {
                this.background = background
                this.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Palette.BORDER),
                        BorderFactory.createEmptyBorder(7, 9, 7, 9),
                    ),
                )
            }
        }
        card.maximumSize = Dimension(if (alignRight) 440 else if (bubbleStyle) 520 else 560, Int.MAX_VALUE)
        if (bubbleStyle) {
            card.border = BorderFactory.createEmptyBorder(9, 11, 9, 11)
        }

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        left.isOpaque = false
        if (bubbleStyle && !alignRight) {
            left.add(createAvatarBadge("AI", accent))
        }
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
        if (bubbleStyle && alignRight) {
            right.add(createAvatarBadge("你", accent))
        }
        right.add(timeLabel)

        val pagination = if (codeBlock) PaginationState.create(text) else null
        val plainBody = JBTextArea(pagination?.currentPageText() ?: text)
        plainBody.isEditable = false
        plainBody.lineWrap = !codeBlock
        plainBody.wrapStyleWord = true
        plainBody.background = if (bubbleStyle) card.background else background
        plainBody.border = BorderFactory.createEmptyBorder()
        plainBody.margin = JBInsets(0, 0, 0, 0)
        if (codeBlock) {
            plainBody.font = Font(Font.MONOSPACED, Font.PLAIN, plainBody.font.size)
            plainBody.background = Palette.CODE_BG
            plainBody.foreground = Palette.CODE_FG
            plainBody.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.CODE_BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            )
        }

        val markdownBody = if (!codeBlock && bubbleStyle && !alignRight) {
            if (outputParts.isNotEmpty()) {
                createOutputPartsView(outputParts, card.background)
            } else {
                createMarkdownView(text, card.background)
            }
        } else {
            null
        }
        val contentView: JComponent = markdownBody ?: plainBody

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
                    plainBody.text = state.currentPageText()
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
            codePanel.add(contentView, BorderLayout.CENTER)
            codePanel
        } else {
            contentView
        }

        if (collapsible) {
            val toggle = JButton(if (startCollapsed) "展开" else "收起")
            toggle.margin = JBInsets(2, 8, 2, 8)
            toggle.addActionListener {
                val expanded = !contentComponent.isVisible
                contentComponent.isVisible = expanded
                toggle.text = if (expanded) "收起" else "展开"
                collapsedState[id] = !expanded
                transcriptPanel.revalidate()
                transcriptPanel.repaint()
            }
            contentComponent.isVisible = !startCollapsed
            right.add(toggle)
            collapsibleBodies[id] = contentComponent
            collapsedState.putIfAbsent(id, startCollapsed)
        }

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        card.add(header, BorderLayout.NORTH)
        card.add(contentComponent, BorderLayout.CENTER)

        transcriptPanel.add(wrapCardRow(card, alignRight))
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 6)))
        messageAreas[id] = { value ->
            if (markdownBody != null) {
                markdownBody.text = if (outputParts.isNotEmpty()) {
                    outputPartsToHtml(outputParts)
                } else {
                    markdownToHtml(value)
                }
            } else {
                plainBody.text = value
            }
        }
    }

    private fun addSystemBanner(
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

        val banner = JPanel(BorderLayout(0, 4))
        banner.maximumSize = Dimension(620, Int.MAX_VALUE)
        banner.background = Palette.SYSTEM_STRIP_BG
        banner.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.SYSTEM_STRIP_BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            ),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        left.isOpaque = false
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        left.add(titleLabel)

        val timeLabel = JBLabel(formatTime(createdAt))
        timeLabel.foreground = JBColor.GRAY

        val body = JBTextArea(text)
        body.isEditable = false
        body.lineWrap = true
        body.wrapStyleWord = true
        body.background = banner.background
        body.border = BorderFactory.createEmptyBorder()
        body.margin = JBInsets(0, 0, 0, 0)

        header.add(left, BorderLayout.WEST)
        header.add(timeLabel, BorderLayout.EAST)
        banner.add(header, BorderLayout.NORTH)
        banner.add(body, BorderLayout.CENTER)
        banner.maximumSize = Dimension(560, banner.preferredSize.height)

        transcriptPanel.add(wrapCardRow(banner, false))
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 6)))
        messageAreas[id] = { value -> body.text = value }
    }

    private fun addToolEventRow(
        id: String,
        stage: String,
        title: String,
        subtitle: String?,
        text: String,
        createdAt: Instant,
        accent: Color,
    ) {
        if (messageAreas.containsKey(id)) return

        val row = JPanel(BorderLayout(0, 0))
        row.maximumSize = Dimension(620, Int.MAX_VALUE)
        row.isOpaque = false
        row.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)

        val gutter = TimelineGutter(accent)
        gutter.border = BorderFactory.createEmptyBorder(0, 0, 0, 8)

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        left.isOpaque = false
        left.add(createMiniTag(stage, accent))
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
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
        val toggle = JButton("展开")
        toggle.margin = JBInsets(2, 8, 2, 8)
        right.add(timeLabel)
        right.add(toggle)

        val pagination = PaginationState.create(text)
        val body = JBTextArea(pagination?.currentPageText() ?: text)
        body.isEditable = false
        body.lineWrap = true
        body.wrapStyleWord = true
        body.font = Font(Font.MONOSPACED, Font.PLAIN, body.font.size)
        body.background = Palette.CODE_BG
        body.foreground = Palette.CODE_FG
        body.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Palette.CODE_BORDER),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )

        val content = JPanel(BorderLayout(0, 6))
        content.isOpaque = false
        content.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        content.isVisible = false

        val contentActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        contentActions.isOpaque = false
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
            contentActions.add(pageLabel)
            contentActions.add(prevButton)
            contentActions.add(nextButton)
        }
        val copyButton = JButton("复制")
        copyButton.margin = JBInsets(2, 8, 2, 8)
        copyButton.addActionListener { copyToClipboard(text) }
        contentActions.add(copyButton)
        val bodyScroll = createBoundedScrollPane(body, Palette.CODE_BG, 220)
        bodyScroll.border = BorderFactory.createEmptyBorder()
        bodyScroll.viewport.background = Palette.CODE_BG
        content.add(contentActions, BorderLayout.NORTH)
        content.add(bodyScroll, BorderLayout.CENTER)

        toggle.addActionListener {
            val expanded = !content.isVisible
            content.isVisible = expanded
            toggle.text = if (expanded) "收起" else "展开"
            transcriptPanel.revalidate()
            transcriptPanel.repaint()
        }

        val center = JPanel(BorderLayout(0, 6))
        center.isOpaque = false
        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        center.add(header, BorderLayout.NORTH)
        center.add(content, BorderLayout.CENTER)

        row.add(gutter, BorderLayout.WEST)
        row.add(center, BorderLayout.CENTER)

        transcriptPanel.add(wrapCardRow(row, false))
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 4)))
        messageAreas[id] = { value -> body.text = value }
        collapsibleBodies[id] = content
    }

    private fun addToolInvocationRow(entry: TranscriptEntry.ToolInvocation) {
        val subtitle = when (entry.state) {
            com.idopen.idopen.agent.ToolInvocationState.PENDING -> "等待中"
            com.idopen.idopen.agent.ToolInvocationState.RUNNING -> "执行中"
            com.idopen.idopen.agent.ToolInvocationState.COMPLETED -> "已完成"
            com.idopen.idopen.agent.ToolInvocationState.ERROR -> "失败"
        }
        val headerSubtitle = entry.title?.takeIf { it.isNotBlank() }?.let { "$subtitle · $it" } ?: subtitle
        val accent = when (entry.state) {
            com.idopen.idopen.agent.ToolInvocationState.ERROR -> Palette.ERROR_ACCENT
            else -> Palette.TOOL_ACCENT
        }
        val details = buildString {
            entry.title?.takeIf { it.isNotBlank() }?.let { title ->
                append("阶段")
                append("\n")
                append(title)
                append("\n\n")
            }
            if (entry.metadata.isNotEmpty()) {
                append("元数据")
                append("\n")
                entry.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                    append("$key: $value")
                    append("\n")
                }
                append("\n")
            }
            append("参数")
            append("\n")
            append(entry.argumentsJson)
            entry.output?.takeIf { it.isNotBlank() }?.let { output ->
                append("\n\n")
                append(if (entry.state == com.idopen.idopen.agent.ToolInvocationState.ERROR) "错误输出" else "执行结果")
                append("\n")
                append(output)
            }
        }
        addToolEventRow(
            id = entry.id,
            stage = "工具",
            title = entry.toolName,
            subtitle = headerSubtitle,
            text = details,
            createdAt = entry.createdAt,
            accent = accent,
        )
    }

    private fun addApprovalCard(entry: TranscriptEntry.Approval) {
        if (collapsibleBodies.containsKey(entry.request.id)) return

        val isCommand = entry.request.type == ApprovalRequest.Type.COMMAND
        val accent = if (isCommand) Palette.APPROVAL_COMMAND_ACCENT else Palette.APPROVAL_PATCH_ACCENT
        val background = if (isCommand) Palette.APPROVAL_COMMAND_BG else Palette.APPROVAL_PATCH_BG

        val card = JPanel(BorderLayout(0, 8))
        card.maximumSize = Dimension(560, Int.MAX_VALUE)
        card.background = background
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
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
        body.foreground = JBColor.foreground()
        body.border = BorderFactory.createEmptyBorder()
        body.margin = JBInsets(0, 0, 0, 0)
        val bodyScroll = createBoundedScrollPane(body, background, 180)
        val detailsPanel = JPanel(BorderLayout())
        detailsPanel.isOpaque = false
        detailsPanel.border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
        detailsPanel.add(bodyScroll, BorderLayout.CENTER)
        detailsPanel.isVisible = false

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

        val summaryLabel = JBLabel(
            if (isCommand) {
                "将在当前项目中执行 Shell 命令"
            } else {
                "将修改目标文件并应用补丁"
            },
        )
        summaryLabel.foreground = JBColor.GRAY
        summaryLabel.background = if (isCommand) Palette.APPROVAL_COMMAND_WARN_BG else Palette.APPROVAL_PATCH_WARN_BG
        summaryLabel.isOpaque = true
        summaryLabel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(if (isCommand) Palette.APPROVAL_COMMAND_WARN_BORDER else Palette.APPROVAL_PATCH_WARN_BORDER),
            BorderFactory.createEmptyBorder(6, 8, 6, 8),
        )

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

            val expanded = detailsPanel.isVisible
            val pending = entry.request.status == ApprovalRequest.Status.PENDING
            actions.isVisible = expanded && pending
            approve.isVisible = pending
            reject.isVisible = pending
            approve.isEnabled = pending
            reject.isEnabled = pending
        }

        toggle.addActionListener {
            val expanded = !detailsPanel.isVisible
            detailsPanel.isVisible = expanded
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
        center.add(summaryLabel)
        center.add(Box.createRigidArea(Dimension(0, 6)))
        center.add(detailsPanel)
        card.add(center, BorderLayout.CENTER)
        card.add(actions, BorderLayout.SOUTH)
        refreshApprovalUi()

        val row = JPanel(BorderLayout(0, 0))
        row.isOpaque = false
        row.maximumSize = Dimension(620, Int.MAX_VALUE)
        val gutter = TimelineGutter(accent)
        gutter.border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
        row.add(gutter, BorderLayout.WEST)
        row.add(card, BorderLayout.CENTER)

        transcriptPanel.add(wrapCardRow(row, false))
        transcriptPanel.add(Box.createRigidArea(Dimension(0, 6)))
        collapsibleBodies[entry.request.id] = detailsPanel
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
        val chip = BubblePanel(
            backgroundColor = Palette.CHIP_BG,
            borderColor = Palette.CHIP_BORDER,
            arc = 16,
        )
        chip.layout = BoxLayout(chip, BoxLayout.X_AXIS)
        chip.border = BorderFactory.createEmptyBorder(5, 9, 5, 9)

        val dot = JPanel()
        dot.isOpaque = true
        dot.background = Palette.CHIP_DOT
        dot.preferredSize = Dimension(7, 7)
        dot.maximumSize = Dimension(7, 7)
        dot.minimumSize = Dimension(7, 7)
        dot.border = BorderFactory.createEmptyBorder()

        val label = JBLabel(text)
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 0.3f)

        chip.add(dot)
        chip.add(Box.createRigidArea(Dimension(7, 0)))
        chip.add(label)
        return chip
    }

    private fun createBoundedScrollPane(content: JComponent, background: Color, maxHeight: Int): JBScrollPane {
        val preferredHeight = content.preferredSize.height.coerceIn(72, maxHeight)
        return JBScrollPane(content).apply {
            border = BorderFactory.createEmptyBorder()
            isOpaque = false
            viewport.isOpaque = true
            viewport.background = background
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBar.unitIncrement = 16
            verticalScrollBar.unitIncrement = 18
            preferredSize = Dimension(0, preferredHeight)
            maximumSize = Dimension(Int.MAX_VALUE, maxHeight)
        }
    }

    private fun createMarkdownView(markdown: String, backgroundColor: Color): JEditorPane {
        return JEditorPane("text/html", markdownToHtml(markdown)).apply {
            isEditable = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
            margin = JBInsets(0, 0, 0, 0)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = JBLabel().font
            foreground = JBColor.foreground()
            caretColor = foreground
            background = backgroundColor
        }
    }

    private fun createOutputPartsView(outputParts: List<AssistantOutputPart>, backgroundColor: Color): JEditorPane {
        return JEditorPane("text/html", outputPartsToHtml(outputParts)).apply {
            isEditable = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
            margin = JBInsets(0, 0, 0, 0)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = JBLabel().font
            foreground = JBColor.foreground()
            caretColor = foreground
            background = backgroundColor
        }
    }

    private fun markdownToHtml(markdown: String): String {
        val foreground = colorToHex(JBColor.foreground())
        val muted = colorToHex(JBColor.GRAY)
        val codeBackground = colorToHex(Palette.CODE_BG)
        val codeBorder = colorToHex(Palette.CODE_BORDER)
        val codeForeground = colorToHex(Palette.CODE_FG)

        val html = StringBuilder()
        val paragraph = mutableListOf<String>()
        val listItems = mutableListOf<String>()
        var orderedList = false
        var inCodeBlock = false
        val codeLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            html.append("<p>")
            html.append(applyInlineMarkdown(paragraph.joinToString(" ")))
            html.append("</p>")
            paragraph.clear()
        }

        fun flushList() {
            if (listItems.isEmpty()) return
            html.append(if (orderedList) "<ol>" else "<ul>")
            listItems.forEach { item ->
                html.append("<li>").append(applyInlineMarkdown(item)).append("</li>")
            }
            html.append(if (orderedList) "</ol>" else "</ul>")
            listItems.clear()
        }

        fun flushCodeBlock() {
            if (!inCodeBlock) return
            html.append("<pre><code>")
            html.append(escapeHtml(codeLines.joinToString("\n")))
            html.append("</code></pre>")
            codeLines.clear()
        }

        markdown.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.startsWith("```")) {
                flushParagraph()
                flushList()
                if (inCodeBlock) {
                    flushCodeBlock()
                }
                inCodeBlock = !inCodeBlock
                return@forEach
            }
            if (inCodeBlock) {
                codeLines += rawLine
                return@forEach
            }
            if (line.isBlank()) {
                flushParagraph()
                flushList()
                return@forEach
            }

            val heading = Regex("^(#{1,3})\\s+(.+)$").matchEntire(line)
            if (heading != null) {
                flushParagraph()
                flushList()
                val level = heading.groupValues[1].length.coerceAtMost(3)
                html.append("<h$level>")
                html.append(applyInlineMarkdown(heading.groupValues[2]))
                html.append("</h$level>")
                return@forEach
            }

            val ordered = Regex("^\\d+\\.\\s+(.+)$").matchEntire(line)
            if (ordered != null) {
                flushParagraph()
                if (listItems.isNotEmpty() && !orderedList) flushList()
                orderedList = true
                listItems += ordered.groupValues[1]
                return@forEach
            }

            val unordered = Regex("^[-*]\\s+(.+)$").matchEntire(line)
            if (unordered != null) {
                flushParagraph()
                if (listItems.isNotEmpty() && orderedList) flushList()
                orderedList = false
                listItems += unordered.groupValues[1]
                return@forEach
            }

            paragraph += line
        }

        flushParagraph()
        flushList()
        if (inCodeBlock) {
            flushCodeBlock()
        }

        return """
            <html>
              <head>
                <style>
                  body { color: $foreground; font-family: sans-serif; font-size: 12px; margin: 0; }
                  p { margin: 0 0 8px 0; }
                  h1, h2, h3 { margin: 8px 0 6px 0; font-weight: 700; }
                  h1 { font-size: 16px; }
                  h2 { font-size: 14px; }
                  h3 { font-size: 13px; }
                  ul, ol { margin: 0 0 8px 18px; padding: 0; }
                  li { margin: 0 0 4px 0; }
                  code { font-family: monospace; color: $codeForeground; background: $codeBackground; border: 1px solid $codeBorder; padding: 1px 4px; }
                  pre { margin: 6px 0 8px 0; padding: 8px 10px; color: $codeForeground; background: $codeBackground; border: 1px solid $codeBorder; }
                  pre code { border: 0; padding: 0; background: transparent; }
                  strong { font-weight: 700; }
                  em { color: $muted; font-style: italic; }
                </style>
              </head>
              <body>$html</body>
            </html>
        """.trimIndent()
    }

    private fun outputPartsToHtml(parts: List<AssistantOutputPart>): String {
        val foreground = colorToHex(JBColor.foreground())
        val muted = colorToHex(JBColor.GRAY)
        val codeBackground = colorToHex(Palette.CODE_BG)
        val codeBorder = colorToHex(Palette.CODE_BORDER)
        val codeForeground = colorToHex(Palette.CODE_FG)
        val html = buildString {
            parts.forEach { part ->
                when (part) {
                    is AssistantOutputPart.Text -> {
                        if (part.text.isNotBlank()) {
                            append("<p>")
                            append(part.text.lines().joinToString("<br/>") { applyInlineMarkdown(it) })
                            append("</p>")
                        }
                    }

                    is AssistantOutputPart.ListBlock -> {
                        append(if (part.ordered) "<ol>" else "<ul>")
                        part.items.forEach { item ->
                            append("<li>")
                            append(applyInlineMarkdown(item))
                            append("</li>")
                        }
                        append(if (part.ordered) "</ol>" else "</ul>")
                    }

                    is AssistantOutputPart.CodeBlock -> {
                        append("<pre><code>")
                        append(escapeHtml(part.code))
                        append("</code></pre>")
                    }
                }
            }
        }
        return """
            <html>
              <head>
                <style>
                  body { color: $foreground; font-family: sans-serif; font-size: 12px; margin: 0; }
                  p { margin: 0 0 8px 0; }
                  ul, ol { margin: 0 0 8px 18px; padding: 0; }
                  li { margin: 0 0 4px 0; }
                  code { font-family: monospace; color: $codeForeground; background: $codeBackground; border: 1px solid $codeBorder; padding: 1px 4px; }
                  pre { margin: 6px 0 8px 0; padding: 8px 10px; color: $codeForeground; background: $codeBackground; border: 1px solid $codeBorder; white-space: pre-wrap; word-break: break-word; }
                  pre code { border: 0; padding: 0; background: transparent; }
                  strong { font-weight: 700; }
                  em { color: $muted; font-style: italic; }
                </style>
              </head>
              <body>$html</body>
            </html>
        """.trimIndent()
    }

    private fun applyInlineMarkdown(text: String): String {
        val codeSegments = mutableListOf<String>()
        var withPlaceholders = text.replace(Regex("`([^`]+)`")) { match ->
            val token = "__CODE_${codeSegments.size}__"
            codeSegments += "<code>${escapeHtml(match.groupValues[1])}</code>"
            token
        }
        withPlaceholders = escapeHtml(withPlaceholders)
        withPlaceholders = withPlaceholders.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        withPlaceholders = withPlaceholders.replace(Regex("(?<!\\*)\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)"), "<em>$1</em>")
        codeSegments.forEachIndexed { index, value ->
            withPlaceholders = withPlaceholders.replace("__CODE_${index}__", value)
        }
        return withPlaceholders
    }

    private fun escapeHtml(text: String): String {
        return buildString(text.length) {
            text.forEach { char ->
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> char
                    },
                )
            }
        }
    }

    private fun colorToHex(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun copyToClipboard(text: String) {
        runCatching {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }.recoverCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    private fun wrapCardRow(card: JComponent, alignRight: Boolean): JComponent {
        val row = object : JPanel() {
            override fun getMaximumSize(): Dimension {
                val preferred = preferredSize
                return Dimension(Int.MAX_VALUE, preferred.height)
            }
        }
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        card.alignmentY = Component.TOP_ALIGNMENT
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
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 1f)
        setPillColors(label, background, border)
        return label
    }

    private fun setPillColors(label: JBLabel, background: Color, border: Color) {
        label.background = background
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border),
            BorderFactory.createEmptyBorder(2, 7, 2, 7),
        )
    }

    private fun createMiniTag(text: String, color: Color): JBLabel {
        val label = JBLabel(text)
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 1f)
        label.foreground = color
        return label
    }

    private fun createAvatarBadge(text: String, color: Color): JComponent {
        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                preferredSize = Dimension(20, 20)
                minimumSize = Dimension(20, 20)
                maximumSize = Dimension(20, 20)
                val label = JBLabel(text, JBLabel.CENTER)
                label.foreground = JBColor(Color(255, 255, 255), Color(255, 255, 255))
                label.font = label.font.deriveFont(Font.BOLD, label.font.size2D - 1f)
                add(label, BorderLayout.CENTER)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(0, 0, width - 1, height - 1)
                g2.dispose()
                super.paintComponent(g)
            }
        }
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

    private class BubblePanel(
        private val backgroundColor: Color,
        private val borderColor: Color,
        private val arc: Int,
    ) : JPanel(BorderLayout(0, 0)) {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = backgroundColor
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = borderColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private class TimelineGutter(
        private val accent: Color,
    ) : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(14, 0)
            minimumSize = Dimension(14, 0)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val x = width / 2
            g2.color = JBColor(Color(214, 218, 224), Color(78, 84, 94))
            g2.drawLine(x, 0, x, height)
            g2.color = accent
            g2.fillOval(x - 3, 10, 6, 6)
            g2.dispose()
        }
    }

    private object Palette {
        val CANVAS = JBColor(Color(245, 246, 248), Color(33, 36, 41))
        val SURFACE = JBColor(Color(255, 255, 255), Color(40, 43, 48))
        val BORDER = JBColor(Color(219, 223, 230), Color(76, 81, 89))
        val HEADER_FIELD_BG = JBColor(Color(247, 248, 251), Color(46, 49, 56))
        val HEADER_FIELD_BORDER = JBColor(Color(206, 211, 218), Color(82, 87, 96))
        val SYSTEM_STRIP_BG = JBColor(Color(249, 247, 241), Color(48, 45, 39))
        val SYSTEM_STRIP_BORDER = JBColor(Color(230, 219, 183), Color(88, 82, 67))

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
        val CHIP_DOT = JBColor(Color(86, 128, 214), Color(120, 164, 255))

        val COMPOSER_BG = JBColor(Color(255, 255, 255), Color(35, 38, 44))
        val COMPOSER_BORDER = JBColor(Color(196, 204, 215), Color(86, 92, 102))
        val COMPOSER_ACTIVE_BORDER = JBColor(Color(94, 142, 231), Color(102, 146, 229))
        val ACTION_SEND_BG = JBColor(Color(78, 119, 214), Color(72, 112, 205))
        val ACTION_SEND_BORDER = JBColor(Color(66, 104, 191), Color(92, 131, 216))
        val ACTION_STOP_BG = JBColor(Color(197, 86, 86), Color(174, 73, 73))
        val ACTION_STOP_BORDER = JBColor(Color(171, 71, 71), Color(196, 97, 97))

        val USER_BUBBLE_BORDER = JBColor(Color(124, 159, 230), Color(86, 126, 214))
        val ASSISTANT_BUBBLE_BORDER = JBColor(Color(116, 181, 143), Color(76, 148, 109))
    }
}
