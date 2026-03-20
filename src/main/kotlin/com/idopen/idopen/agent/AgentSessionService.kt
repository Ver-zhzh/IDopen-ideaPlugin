package com.idopen.idopen.agent

import com.idopen.idopen.settings.DisplayLanguage
import com.idopen.idopen.settings.IDopenSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class AgentSessionService(private val project: Project) {
    companion object {
        private const val MAX_AGENT_TURNS = 24
        private const val MAX_TOOL_CALLS = 48
        private const val DEFAULT_SESSION_TITLE = "新对话"
    }

    private val client = OpenAICompatibleClient()
    private val sessionStore = project.getService(AgentSessionStore::class.java)
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()
    private val capabilityCache = ConcurrentHashMap<String, ToolCapability>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val idCounter = AtomicInteger()
    private val tools = IntelliJAgentTools(
        project = project,
        shellSettings = { IDopenSettingsState.getInstance() },
        approvalRequester = ::requestApproval,
        todoReader = ::currentTodoItems,
        todoWriter = ::replaceTodoItems,
    )
    private val sessions = linkedMapOf<String, SessionState>()

    @Volatile
    private var activeSessionId: String = ""

    @Volatile
    private var currentRun: Future<*>? = null

    @Volatile
    private var currentRunSessionId: String? = null

    @Volatile
    private var currentRunRoundId: String? = null

    init {
        val restored = sessionStore.restore()
        if (restored != null && restored.sessions.isNotEmpty()) {
            restored.sessions.forEach { persisted ->
                val stepGroups = SessionStepSupport.group(persisted.transcript)
                sessions[persisted.id] = SessionState(
                    id = persisted.id,
                    title = persisted.title,
                    todos = persisted.todos.toMutableList(),
                    transcript = persisted.transcript.toMutableList(),
                    stepGroups = stepGroups,
                    steps = SessionStepSupport.buildSteps(stepGroups),
                    history = persisted.history.toMutableList(),
                    updatedAt = persisted.updatedAt,
                    lastCapabilityNotice = persisted.lastCapabilityNotice,
                    activeProjectAgent = normalizeProjectAgentName(persisted.activeProjectAgent),
                )
                syncSessionSystemPrompt(sessions.getValue(persisted.id))
            }
            activeSessionId = restored.activeSessionId.takeIf { sessions.containsKey(it) }
                ?: restored.sessions.first().id
            idCounter.set(SessionPersistenceSupport.highestGeneratedId(restored))
        } else {
            val firstSession = createSessionInternal(DEFAULT_SESSION_TITLE)
            activeSessionId = firstSession.id
            persistSessions()
        }
        emit(SessionEvent.SessionsChanged(getSessions(), activeSessionId))
        emitSnapshotChanged(currentSession())
    }

    fun sendUserMessage(
        text: String,
        attachments: List<AttachmentContext> = emptyList(),
        turnOptions: TurnExecutionOptions = TurnExecutionOptions(),
    ) {
        if (text.isBlank()) return
        if (isRunning()) {
            emitFailure(currentSession(), "当前已有任务在运行，请先停止后再发送新消息。")
            return
        }

        val settings = IDopenSettingsState.getInstance()
        val session = currentSession()
        val userText = text.trim()
        val roundId = nextId("round")

        SessionTitleSupport.pickTitle(session.title, DEFAULT_SESSION_TITLE, userText)?.let { title ->
            session.title = title
            persistSessions()
        }

        normalizeTurnOptions(turnOptions)?.let { normalized ->
            session.turnOverrides[roundId] = normalized
            buildTurnOverrideNotice(normalized)?.let { notice ->
                val noticeEntry = TranscriptEntry.System(
                    id = nextId("system"),
                    message = notice,
                    roundId = roundId,
                )
                appendEntry(session, noticeEntry)
                emitEntryAdded(session, noticeEntry)
            }
        }

        if (attachments.isNotEmpty()) {
            val prepared = AttachmentPromptSupport.prepare(
                attachments = attachments,
                toolCallingEnabled = resolveToolCallingMode(settings) != ToolCallingMode.DISABLED,
            )
            val contextEntry = TranscriptEntry.Context(
                id = nextId("context"),
                summary = prepared.transcriptSummary,
                roundId = roundId,
            )
            appendEntry(session, contextEntry)
            session.history += ConversationMessage.System(prepared.injectedPrompt, roundId)
            persistSessions()
            emitEntryAdded(session, contextEntry)
        }

        val userEntry = TranscriptEntry.User(
            id = nextId("user"),
            text = userText,
            roundId = roundId,
        )
        appendEntry(session, userEntry)
        session.history += ConversationMessage.User(userText, roundId)
        persistSessions()
        emitEntryAdded(session, userEntry)
        emitSessionsChanged()
        startRun(session.id, roundId)
    }

    fun stopCurrentRun() {
        currentRun?.cancel(true)
        emit(SessionEvent.RunStateChanged(false))
    }

    fun approveCommand(callId: String, approved: Boolean) {
        resolveApproval(callId, approved)
    }

    fun approvePatch(callId: String, approved: Boolean) {
        resolveApproval(callId, approved)
    }

    fun subscribe(listener: SessionListener): SessionSubscription {
        listeners += listener
        return SessionSubscription { listeners.remove(listener) }
    }

    fun getTranscript(): List<TranscriptEntry> = currentSession().transcript.toList()

    fun getStepGroups(): List<SessionStepGroup> = currentSession().stepGroups.toList()

    fun getSteps(): List<SessionStep> = currentSession().steps.toList()

    fun getCurrentSessionSnapshot(): ChatSessionSnapshot = snapshot(currentSession())

    fun getSessions(): List<ChatSessionSummary> {
        return sessions.values.map { session ->
            ChatSessionSummary(
                id = session.id,
                title = session.title,
                updatedAt = session.updatedAt,
                entryCount = session.transcript.size,
                running = currentRunSessionId == session.id && isRunning(),
                activeAgentName = session.activeProjectAgent,
            )
        }
    }

    fun getCurrentSessionId(): String = activeSessionId

    fun createSession(): String {
        if (isRunning()) return activeSessionId
        val session = createSessionInternal(DEFAULT_SESSION_TITLE)
        activeSessionId = session.id
        emitSessionsChanged()
        return session.id
    }

    fun deleteSession(sessionId: String): Boolean {
        if (isRunning()) return false
        val removed = sessions.remove(sessionId) ?: return false
        pendingApprovals.entries.removeIf { it.value.sessionId == removed.id }

        if (sessions.isEmpty()) {
            val replacement = createSessionInternal(DEFAULT_SESSION_TITLE)
            activeSessionId = replacement.id
        } else if (activeSessionId == removed.id) {
            activeSessionId = sessions.values.maxByOrNull { it.updatedAt }?.id ?: sessions.keys.first()
        }

        emitSessionsChanged()
        return true
    }

    fun selectSession(sessionId: String) {
        if (isRunning()) return
        if (!sessions.containsKey(sessionId) || activeSessionId == sessionId) return
        activeSessionId = sessionId
        emitSessionsChanged()
    }

    fun activateProjectAgent(name: String): LoadedProjectAgent? {
        if (isRunning()) return null
        val agent = ProjectAgentSupport.find(projectRoot(), name) ?: return null
        val session = currentSession()
        val changed = session.activeProjectAgent != agent.name
        session.activeProjectAgent = agent.name
        syncSessionSystemPrompt(session)
        if (changed) {
            appendEntry(
                session,
                TranscriptEntry.System(
                    id = nextId("system"),
                    message = projectAgentActivatedMessage(agent),
                ),
            )
            emitEntryAdded(session, session.transcript.last())
        } else {
            session.updatedAt = Instant.now()
            persistSessions()
            emitSnapshotChanged(session)
        }
        emit(SessionEvent.SessionsChanged(getSessions(), activeSessionId))
        return agent
    }

    fun clearActiveProjectAgent(): String? {
        if (isRunning()) return null
        val session = currentSession()
        val previous = session.activeProjectAgent ?: return ""
        session.activeProjectAgent = null
        syncSessionSystemPrompt(session)
        appendEntry(
            session,
            TranscriptEntry.System(
                id = nextId("system"),
                message = projectAgentClearedMessage(previous),
            ),
        )
        emitEntryAdded(session, session.transcript.last())
        emit(SessionEvent.SessionsChanged(getSessions(), activeSessionId))
        return previous
    }

    fun isRunning(): Boolean = currentRun?.isDone == false

    private fun startRun(sessionId: String, roundId: String) {
        currentRunSessionId = sessionId
        currentRunRoundId = roundId
        currentRun = executor.submit {
            emit(SessionEvent.RunStateChanged(true))
            emitSessionsChanged()
            try {
                runCatching { agentLoop(sessionId, roundId) }
                .onFailure { emitFailure(session(sessionId), it.message ?: "未知错误", roundId) }
            } finally {
                session(sessionId)?.turnOverrides?.remove(roundId)
                emit(SessionEvent.RunStateChanged(false))
                currentRunSessionId = null
                currentRunRoundId = null
                emitSessionsChanged()
            }
        }
    }

    private fun agentLoop(sessionId: String, roundId: String) {
        val session = session(sessionId) ?: return
        val settings = IDopenSettingsState.getInstance()
        val unlimitedUsage = settings.unlimitedUsage
        val provider = ProviderConfigSupport.fromSettings(settings)
        if (provider.error != null) {
            emitFailure(session, provider.error, roundId)
            return
        }

        val config = provider.config ?: return
        val effectiveConfig = effectiveProviderConfig(session, config, roundId)
        val runtimeProfile = resolveRuntimeProfile(session, effectiveConfig, settings, roundId)
        val toolDefinitions = if (runtimeProfile.includeTools) tools.definitions() else emptyList()
        var totalToolCalls = 0

        repeat(if (unlimitedUsage) Int.MAX_VALUE else MAX_AGENT_TURNS) { turnIndex ->
            if (Thread.currentThread().isInterrupted) return
            val stepIndex = turnIndex + 1
            val stepStart = TranscriptEntry.StepStart(
                id = nextId("step-start"),
                stepIndex = stepIndex,
                roundId = roundId,
            )
            appendEntry(session, stepStart)
            emitEntryAdded(session, stepStart)

            var assistantEntry: TranscriptEntry.Assistant? = null
            var stepToolCalls = 0
            var stepSucceeded = true
            val turnPlan = AgentPlanningSupport.buildPlan(
                snapshot = snapshot(session),
                roundId = roundId,
                userRequest = latestRoundUserText(session, roundId),
                availableTools = toolDefinitions,
                runtimeProfile = runtimeProfile,
            )
            val result = client.streamChat(
                OpenAICompatibleClient.ChatRequest(
                    providerConfig = effectiveConfig,
                    messages = buildTurnMessages(
                        session = session,
                        roundId = roundId,
                        turnPlan = turnPlan,
                    ),
                    tools = toolDefinitions,
                    sessionId = session.id,
                ),
            ) { delta ->
                val entry = assistantEntry ?: TranscriptEntry.Assistant(
                    id = nextId("assistant"),
                    text = "",
                    outputParts = emptyList(),
                    roundId = roundId,
                ).also {
                    assistantEntry = it
                    appendEntry(session, it)
                    emitEntryAdded(session, it)
                }
                entry.text = delta.snapshot
                entry.outputParts = delta.outputParts
                session.updatedAt = Instant.now()
                emit(SessionEvent.MessageDelta(entry.id, delta.delta, entry.text, entry.outputParts))
            }

            if (assistantEntry == null && result.text.isNotBlank()) {
                val entry = TranscriptEntry.Assistant(
                    id = nextId("assistant"),
                    text = result.text,
                    outputParts = result.outputParts,
                    roundId = roundId,
                )
                appendEntry(session, entry)
                assistantEntry = entry
                emitEntryAdded(session, entry)
            } else {
                assistantEntry?.outputParts = result.outputParts
            }

            session.history += ConversationMessage.Assistant(
                content = result.text,
                toolCalls = result.toolCalls,
                outputParts = result.outputParts,
                responseItems = result.responseItems,
                roundId = roundId,
            )
            persistSessions()

            if (result.toolCalls.isEmpty()) {
                emitStepFinish(session, stepIndex, "final", 0, true, roundId)
                emit(SessionEvent.RunCompleted("助手回复完成。"))
                return
            }

            if (!runtimeProfile.includeTools) {
                emitStepFinish(session, stepIndex, "tool-disabled", 0, false, roundId)
                emitFailure(session, "当前模型未启用工具调用，但返回了工具请求。", roundId)
                return
            }

            for (toolCall in result.toolCalls) {
                if (Thread.currentThread().isInterrupted) return
                totalToolCalls += 1
                stepToolCalls += 1
                if (!unlimitedUsage && totalToolCalls > MAX_TOOL_CALLS) {
                    emitStepFinish(session, stepIndex, "tool-limit", stepToolCalls, false, roundId)
                    emitFailure(session, "已达到工具调用安全上限（$MAX_TOOL_CALLS 次），任务已停止。", roundId)
                    return
                }

                val toolEntry = TranscriptEntry.ToolInvocation(
                    id = nextId("tool"),
                    callId = toolCall.id,
                    toolName = toolCall.name,
                    argumentsJson = toolCall.argumentsJson,
                    state = ToolInvocationState.PENDING,
                    title = "等待执行",
                    roundId = roundId,
                )
                appendEntry(session, toolEntry)
                emitEntryAdded(session, toolEntry)
                emit(SessionEvent.ToolRequested(toolCall.id, toolCall.name, toolCall.argumentsJson))

                val output = runCatching {
                    tools.execute(toolCall) { update ->
                        applyToolProgress(session, toolEntry, update)
                    }
                }
                    .getOrElse { ToolExecutionResult("工具执行失败：${it.message}", success = false) }
                if (!output.success) {
                    stepSucceeded = false
                }

                if (!output.success) {
                    (output.recoveryHint ?: FailureRecoverySupport.toolHint(
                        toolName = toolCall.name,
                        failureText = output.content,
                        argumentsJson = toolCall.argumentsJson,
                    ))?.let { appendRecoveryHint(session, it, roundId) }
                }

                toolEntry.state = if (output.success) {
                    ToolInvocationState.COMPLETED
                } else {
                    ToolInvocationState.ERROR
                }
                if (toolEntry.startedAt == null) {
                    toolEntry.startedAt = toolEntry.createdAt
                }
                toolEntry.finishedAt = Instant.now()
                toolEntry.output = output.content
                toolEntry.success = output.success
                toolEntry.recoveryHint = output.recoveryHint ?: if (!output.success) {
                    FailureRecoverySupport.toolHint(
                        toolName = toolCall.name,
                        failureText = output.content,
                        argumentsJson = toolCall.argumentsJson,
                    )
                } else {
                    null
                }
                session.history += ConversationMessage.Tool(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    content = output.content,
                    roundId = roundId,
                )
                emitEntryUpdated(session, toolEntry)
                emit(SessionEvent.ToolCompleted(toolCall.id, toolCall.name, output.content, output.success))
            }

            emitStepFinish(session, stepIndex, "tool-loop", stepToolCalls, stepSucceeded, roundId)
        }

        if (!unlimitedUsage) {
            emitFailure(session, "已达到代理推理轮数上限（$MAX_AGENT_TURNS 轮），任务已停止。", roundId)
        }
    }

    private fun resolveRuntimeProfile(
        session: SessionState,
        config: ProviderConfig,
        settings: IDopenSettingsState,
        roundId: String?,
    ): ProviderRuntimeProfile {
        val profile = ProviderRuntimeSupport.resolveProfile(
            config = config,
            settings = settings,
            capabilityLookup = { candidate ->
                capabilityCache.computeIfAbsent(capabilityKey(candidate)) {
                    client.detectToolCapability(candidate)
                }
            },
        )
        if (!profile.includeTools && resolveToolCallingMode(settings) == ToolCallingMode.AUTO) {
            emitAutoModeNotice(
                session = session,
                capability = ToolCapability(
                    supportsToolCalling = profile.supportsToolCalling,
                    detail = profile.capabilityDetail,
                ),
                roundId = roundId,
            )
        }
        return profile
    }

    private fun emitAutoModeNotice(session: SessionState, capability: ToolCapability, roundId: String?) {
        val detail = capability.detail?.takeIf { it.isNotBlank() } ?: "当前模型未通过工具调用探测。"
        if (session.lastCapabilityNotice == detail) return
        session.lastCapabilityNotice = detail
        val entry = TranscriptEntry.System(
            id = nextId("system"),
            message = "已自动退回纯聊天模式：$detail",
            roundId = roundId,
        )
        appendEntry(session, entry)
        emitEntryAdded(session, entry)
    }

    private fun requestApproval(request: ApprovalRequest): CompletableFuture<Boolean> {
        val targetSession = currentRunSessionId?.let(::session) ?: currentSession()
        val roundId = currentRunRoundId

        if (IDopenSettingsState.getInstance().trustMode) {
            request.status = ApprovalRequest.Status.APPROVED
            val entry = TranscriptEntry.Approval(
                id = nextId("approval-entry"),
                request = request,
                roundId = roundId,
            )
            appendEntry(targetSession, entry)
            emitEntryAdded(targetSession, entry)
            return CompletableFuture.completedFuture(true)
        }

        val future = CompletableFuture<Boolean>()
        pendingApprovals[request.id] = PendingApproval(targetSession.id, request, future)
        val entry = TranscriptEntry.Approval(
            id = nextId("approval-entry"),
            request = request,
            roundId = roundId,
        )
        appendEntry(targetSession, entry)
        emitEntryAdded(targetSession, entry)
        emit(SessionEvent.ApprovalRequested(request))
        return future
    }

    private fun resolveApproval(callId: String, approved: Boolean) {
        val pending = pendingApprovals.remove(callId) ?: return
        pending.request.status = if (approved) {
            ApprovalRequest.Status.APPROVED
        } else {
            ApprovalRequest.Status.REJECTED
        }
        persistSessions()
        session(pending.sessionId)?.let {
            if (!approved) {
                appendRecoveryHint(it, FailureRecoverySupport.approvalHint(pending.request), currentRunRoundId)
            }
            it.steps = SessionStepSupport.buildSteps(it.stepGroups)
            emitSnapshotChanged(it)
        }
        pending.future.complete(approved)
    }

    private fun emitFailure(session: SessionState?, message: String, roundId: String? = currentRunRoundId) {
        val target = session ?: currentSession()
        val recoveryHint = FailureRecoverySupport.runtimeHint(message)
        recoveryHint?.let { appendRecoveryHint(target, it, roundId) }
        val entry = TranscriptEntry.Error(
            id = nextId("error"),
            message = message,
            recoveryHint = recoveryHint,
            roundId = roundId,
        )
        appendEntry(target, entry)
        emitEntryAdded(target, entry)
        emit(SessionEvent.RunFailed(message))
    }

    private fun emit(event: SessionEvent) {
        listeners.forEach { it.onEvent(event) }
    }

    private fun emitSessionsChanged() {
        persistSessions()
        emit(SessionEvent.SessionsChanged(getSessions(), activeSessionId))
        emitSnapshotChanged(currentSession())
    }

    private fun appendEntry(session: SessionState, entry: TranscriptEntry) {
        session.transcript += entry
        session.stepGroups = SessionStepSupport.append(session.stepGroups, entry)
        session.steps = SessionStepSupport.buildSteps(session.stepGroups)
        session.updatedAt = Instant.now()
        persistSessions()
    }

    private fun emitEntryAdded(session: SessionState, entry: TranscriptEntry) {
        emit(SessionEvent.EntryAdded(entry))
        emitSnapshotChanged(session)
    }

    private fun emitEntryUpdated(session: SessionState, entry: TranscriptEntry) {
        session.steps = SessionStepSupport.buildSteps(session.stepGroups)
        session.updatedAt = Instant.now()
        persistSessions()
        emit(SessionEvent.EntryUpdated(entry))
        emitSnapshotChanged(session)
    }

    private fun applyToolProgress(
        session: SessionState,
        entry: TranscriptEntry.ToolInvocation,
        update: ToolProgressUpdate,
    ) {
        entry.state = update.state
        if (!update.title.isNullOrBlank()) {
            entry.title = update.title
        }
        if (update.metadata.isNotEmpty()) {
            entry.metadata = update.metadata
        }
        if (update.state == ToolInvocationState.RUNNING && entry.startedAt == null) {
            entry.startedAt = Instant.now()
        }
        if (update.state == ToolInvocationState.COMPLETED || update.state == ToolInvocationState.ERROR) {
            entry.finishedAt = Instant.now()
        }
        emitEntryUpdated(session, entry)
    }

    private fun emitStepFinish(
        session: SessionState,
        stepIndex: Int,
        reason: String,
        toolCalls: Int,
        success: Boolean,
        roundId: String,
    ) {
        val entry = TranscriptEntry.StepFinish(
            id = nextId("step-finish"),
            stepIndex = stepIndex,
            reason = reason,
            toolCalls = toolCalls,
            success = success,
            roundId = roundId,
        )
        appendEntry(session, entry)
        emitEntryAdded(session, entry)
    }

    private fun createSessionInternal(title: String): SessionState {
        val settings = IDopenSettingsState.getInstance()
        val language = DisplayLanguage.fromStored(settings.displayLanguage)
        val providerDefinition = ProviderDefinitionSupport.definition(ProviderType.fromStored(settings.providerType))
        val welcomeMessage = if (providerDefinition.isReady(settings)) {
            if (language == DisplayLanguage.ZH_CN) {
                "IDopen 已就绪。直接发送你的需求即可开始。"
            } else {
                "IDopen is ready. Send your request to start."
            }
        } else {
            if (language == DisplayLanguage.ZH_CN) {
                "IDopen 已就绪。${providerDefinition.setupHint(language)}"
            } else {
                "IDopen is ready. ${providerDefinition.setupHint(language)}"
            }
        }
        val session = SessionState(
            id = nextId("session"),
            title = title,
            todos = mutableListOf(),
            transcript = mutableListOf(),
            stepGroups = emptyList(),
            steps = emptyList(),
            history = mutableListOf(),
            activeProjectAgent = null,
        )
        session.history += ConversationMessage.System(systemPrompt(session))
        session.transcript += TranscriptEntry.System(
            id = nextId("system"),
            message = "IDopen 已就绪。请先配置 OpenAI-compatible 接口，然后开始对话。",
        )
        session.transcript[session.transcript.lastIndex] = (session.transcript.last() as? TranscriptEntry.System)?.copy(
            message = welcomeMessage,
        ) ?: session.transcript.last()
        sessions[session.id] = session
        return session
    }

    private fun currentSession(): SessionState = sessions.getValue(activeSessionId)

    private fun session(sessionId: String): SessionState? = sessions[sessionId]

    private fun currentTodoSession(): SessionState {
        val running = currentRunSessionId?.let(::session)
        return running ?: currentSession()
    }

    private fun currentTodoItems(): List<SessionTodoItem> = currentTodoSession().todos.toList()

    private fun replaceTodoItems(items: List<SessionTodoItem>) {
        val session = currentTodoSession()
        session.todos.clear()
        session.todos.addAll(items)
        session.updatedAt = Instant.now()
        persistSessions()
        emitSessionsChanged()
        emitSnapshotChanged(session)
    }

    private fun normalizeTurnOptions(turnOptions: TurnExecutionOptions): TurnExecutionOptions? {
        val prompt = turnOptions.systemPromptOverride?.trim().takeIf { !it.isNullOrBlank() }
        val model = turnOptions.modelOverride?.trim().takeIf { !it.isNullOrBlank() }
        val label = turnOptions.sourceLabel?.trim().takeIf { !it.isNullOrBlank() }
        if (prompt == null && model == null && label == null) return null
        return TurnExecutionOptions(
            systemPromptOverride = prompt,
            modelOverride = model,
            sourceLabel = label,
        )
    }

    private fun buildTurnOverrideNotice(turnOptions: TurnExecutionOptions): String? {
        if (turnOptions.modelOverride.isNullOrBlank() && turnOptions.sourceLabel.isNullOrBlank()) return null
        val language = DisplayLanguage.fromStored(IDopenSettingsState.getInstance().displayLanguage)
        return if (language == DisplayLanguage.ZH_CN) {
            buildString {
                append("已应用本轮命令配置")
                turnOptions.sourceLabel?.let {
                    append(" ")
                    append(it)
                }
                turnOptions.modelOverride?.let {
                    append("，模型覆盖为 ")
                    append(it)
                }
                append("。")
            }
        } else {
            buildString {
                append("Applied turn configuration")
                turnOptions.sourceLabel?.let {
                    append(" ")
                    append(it)
                }
                turnOptions.modelOverride?.let {
                    append(" with model override ")
                    append(it)
                }
                append(".")
            }
        }
    }

    private fun effectiveProviderConfig(session: SessionState, config: ProviderConfig, roundId: String): ProviderConfig {
        val turnOverrideModel = session.turnOverrides[roundId]?.modelOverride?.takeIf { it.isNotBlank() }
        val overrideModel = turnOverrideModel
            ?: resolveActiveProjectAgent(session)?.model?.takeIf { it.isNotBlank() }
            ?: return config
        return config.copy(model = overrideModel)
    }

    private fun resolveActiveProjectAgent(session: SessionState): LoadedProjectAgent? {
        val name = session.activeProjectAgent ?: return null
        return ProjectAgentSupport.find(projectRoot(), name)
    }

    private fun normalizeProjectAgentName(name: String?): String? {
        val candidate = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ProjectAgentSupport.find(projectRoot(), candidate)?.name
    }

    private fun syncSessionSystemPrompt(session: SessionState) {
        val prompt = systemPrompt(session)
        val current = session.history.firstOrNull()
        if (current is ConversationMessage.System && current.roundId == null) {
            session.history[0] = current.copy(content = prompt)
        } else {
            session.history.add(0, ConversationMessage.System(prompt))
        }
        session.updatedAt = Instant.now()
        persistSessions()
    }

    private fun snapshot(session: SessionState): ChatSessionSnapshot {
        return ChatSessionSnapshot(
            sessionId = session.id,
            title = session.title,
            updatedAt = session.updatedAt,
            running = currentRunSessionId == session.id && isRunning(),
            todos = session.todos.toList(),
            transcript = session.transcript.toList(),
            stepGroups = session.stepGroups.toList(),
            steps = session.steps.toList(),
            activeAgentName = session.activeProjectAgent,
        )
    }

    private fun projectRoot(): java.nio.file.Path = Paths.get(project.basePath ?: ".").toAbsolutePath().normalize()

    private fun projectAgentActivatedMessage(agent: LoadedProjectAgent): String {
        val language = DisplayLanguage.fromStored(IDopenSettingsState.getInstance().displayLanguage)
        return if (language == DisplayLanguage.ZH_CN) {
            buildString {
                append("已启用项目 agent @")
                append(agent.name)
                agent.model?.takeIf { it.isNotBlank() }?.let {
                    append("，模型覆盖为 ")
                    append(it)
                }
                append("。后续轮次会附加该 agent 提示。")
            }
        } else {
            buildString {
                append("Activated project agent @")
                append(agent.name)
                agent.model?.takeIf { it.isNotBlank() }?.let {
                    append(" with model override ")
                    append(it)
                }
                append(". Future turns will include this agent prompt.")
            }
        }
    }

    private fun projectAgentClearedMessage(name: String): String {
        val language = DisplayLanguage.fromStored(IDopenSettingsState.getInstance().displayLanguage)
        return if (language == DisplayLanguage.ZH_CN) {
            "已清除项目 agent @$name，后续轮次将恢复默认 provider 配置。"
        } else {
            "Cleared project agent @$name. Future turns will use the default provider configuration."
        }
    }

    private fun buildTurnMessages(
        session: SessionState,
        roundId: String,
        turnPlan: AgentTurnPlan,
    ): List<ConversationMessage> {
        val compacted = ContextWindowSupport.compact(
            messages = session.history.toList(),
            steps = session.steps,
        )
        val planMessages = buildList {
            session.turnOverrides[roundId]?.systemPromptOverride?.takeIf { it.isNotBlank() }?.let {
                add(ConversationMessage.System(it, roundId))
            }
            addAll(turnPlan.asSystemMessages(roundId))
        }
        return ContextWindowSupport.prepareRequestMessages(
            messages = compacted,
            prefixedSystemMessages = planMessages,
        )
    }

    private fun latestRoundUserText(session: SessionState, roundId: String): String {
        return session.transcript
            .asReversed()
            .filterIsInstance<TranscriptEntry.User>()
            .firstOrNull { it.roundId == roundId }
            ?.text
            .orEmpty()
    }

    private fun emitSnapshotChanged(session: SessionState) {
        emit(SessionEvent.SessionSnapshotChanged(snapshot(session)))
    }

    private fun appendRecoveryHint(session: SessionState, hint: String, roundId: String?) {
        session.history += ConversationMessage.System(
            content = "Recovery hint: $hint",
            roundId = roundId,
        )
        persistSessions()
    }

    private fun summarizeTitle(text: String): String {
        return SessionTitleSupport.summarize(text) ?: DEFAULT_SESSION_TITLE
    }

    private fun resolveToolCallingMode(settings: IDopenSettingsState): ToolCallingMode {
        return ToolCallingMode.fromStored(settings.toolCallingMode)
    }

    private fun capabilityKey(config: ProviderConfig): String {
        return listOf(
            config.baseUrl,
            config.model,
            config.headers.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" },
        ).joinToString("|")
    }

    private fun nextId(prefix: String): String = "$prefix-${idCounter.incrementAndGet()}"

    private fun persistSessions() {
        if (activeSessionId.isBlank()) return
        sessionStore.save(
            activeSessionId = activeSessionId,
            sessions = sessions.values.map { session ->
                PersistedSessionState(
                    id = session.id,
                    title = session.title,
                    todos = session.todos.toList(),
                    transcript = session.transcript.toList(),
                    history = session.history.toList(),
                    updatedAt = session.updatedAt,
                    lastCapabilityNotice = session.lastCapabilityNotice,
                    activeProjectAgent = session.activeProjectAgent,
                )
            },
        )
    }

    private fun systemPrompt(session: SessionState): String {
        val projectRoot = projectRoot()
        val activeAgent = resolveActiveProjectAgent(session)
        return buildString {
            appendLine("You are IDopen, a coding agent running inside IntelliJ IDEA.")
            appendLine("Work only inside the current project: $projectRoot")
            appendLine("Prefer reading files and searching the project before suggesting changes.")
            appendLine("When IDE context references are attached, treat them as hints and inspect exact code with IDE tools.")
            appendLine("Use read_file(path, offset, limit) for targeted reads instead of assuming full-file context.")
            appendLine("Use apply_patch_preview with edits for focused changes, or newContent for full-file rewrites.")
            appendLine("Use run_command only when it materially helps.")
            appendLine("For tasks with multiple concrete steps, keep the session todo list current with todo_write and review it with todo_read before resuming work.")
            SkillSupport.systemPromptSection(projectRoot)?.let {
                appendLine(it)
            }
            McpSupport.systemPromptSection(projectRoot)?.let {
                appendLine(it)
            }
            activeAgent?.let { agent ->
                appendLine("The current session is using project agent @${agent.name}.")
                agent.model?.takeIf { it.isNotBlank() }?.let { model ->
                    appendLine("Use the project agent model override when supported: $model")
                }
                appendLine("Follow these additional project-agent instructions exactly:")
                appendLine(agent.prompt.trim())
            }
            appendLine("Safe read-only commands may run without approval, mutating commands require approval, and dangerous commands are blocked.")
            appendLine("You are connected through the configured provider API.")
            append("The user may write in Chinese. Reply in the user's language.")
        }
    }

    private data class SessionState(
        val id: String,
        var title: String,
        val todos: MutableList<SessionTodoItem>,
        val transcript: MutableList<TranscriptEntry>,
        var stepGroups: List<SessionStepGroup>,
        var steps: List<SessionStep>,
        val history: MutableList<ConversationMessage>,
        val turnOverrides: MutableMap<String, TurnExecutionOptions> = mutableMapOf(),
        var updatedAt: Instant = Instant.now(),
        var lastCapabilityNotice: String? = null,
        var activeProjectAgent: String? = null,
    )

    private data class PendingApproval(
        val sessionId: String,
        val request: ApprovalRequest,
        val future: CompletableFuture<Boolean>,
    )
}
