package com.idopen.idopen.agent

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
    private val tools = IntelliJAgentTools(project, { IDopenSettingsState.getInstance() }, ::requestApproval)
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
                sessions[persisted.id] = SessionState(
                    id = persisted.id,
                    title = persisted.title,
                    transcript = persisted.transcript.toMutableList(),
                    stepGroups = SessionStepSupport.group(persisted.transcript),
                    history = persisted.history.toMutableList(),
                    updatedAt = persisted.updatedAt,
                    lastCapabilityNotice = persisted.lastCapabilityNotice,
                )
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

    fun sendUserMessage(text: String, attachments: List<AttachmentContext> = emptyList()) {
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

    fun getCurrentSessionSnapshot(): ChatSessionSnapshot = snapshot(currentSession())

    fun getSessions(): List<ChatSessionSummary> {
        return sessions.values.map { session ->
            ChatSessionSummary(
                id = session.id,
                title = session.title,
                updatedAt = session.updatedAt,
                entryCount = session.transcript.size,
                running = currentRunSessionId == session.id && isRunning(),
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

    fun selectSession(sessionId: String) {
        if (isRunning()) return
        if (!sessions.containsKey(sessionId) || activeSessionId == sessionId) return
        activeSessionId = sessionId
        emitSessionsChanged()
    }

    fun isRunning(): Boolean = currentRun?.isDone == false

    private fun startRun(sessionId: String, roundId: String) {
        currentRunSessionId = sessionId
        currentRunRoundId = roundId
        currentRun = executor.submit {
            emit(SessionEvent.RunStateChanged(true))
            emitSessionsChanged()
            runCatching { agentLoop(sessionId, roundId) }
                .onFailure { emitFailure(session(sessionId), it.message ?: "未知错误", roundId) }
            emit(SessionEvent.RunStateChanged(false))
            currentRunSessionId = null
            currentRunRoundId = null
            emitSessionsChanged()
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
        val toolDefinitions = resolveToolDefinitions(session, config, settings, roundId)
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
            val result = client.streamChat(
                OpenAICompatibleClient.ChatRequest(
                    providerConfig = config,
                    messages = ContextWindowSupport.compact(
                        messages = session.history.toList(),
                        stepGroups = session.stepGroups,
                    ),
                    tools = toolDefinitions,
                ),
            ) { delta ->
                val entry = assistantEntry ?: TranscriptEntry.Assistant(
                    id = nextId("assistant"),
                    text = "",
                    roundId = roundId,
                ).also {
                    assistantEntry = it
                    appendEntry(session, it)
                    emitEntryAdded(session, it)
                }
                entry.text += delta
                session.updatedAt = Instant.now()
                persistSessions()
                emit(SessionEvent.MessageDelta(entry.id, delta, entry.text))
            }

            if (assistantEntry == null && result.text.isNotBlank()) {
                val entry = TranscriptEntry.Assistant(
                    id = nextId("assistant"),
                    text = result.text,
                    roundId = roundId,
                )
                appendEntry(session, entry)
                assistantEntry = entry
                emitEntryAdded(session, entry)
            }

            session.history += ConversationMessage.Assistant(
                content = result.text,
                toolCalls = result.toolCalls,
                roundId = roundId,
            )
            persistSessions()

            if (result.toolCalls.isEmpty()) {
                emitStepFinish(session, stepIndex, "final", 0, true, roundId)
                emit(SessionEvent.RunCompleted("助手回复完成。"))
                return
            }

            if (toolDefinitions.isEmpty()) {
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

    private fun resolveToolDefinitions(
        session: SessionState,
        config: ProviderConfig,
        settings: IDopenSettingsState,
        roundId: String?,
    ): List<ToolDefinition> {
        return when (resolveToolCallingMode(settings)) {
            ToolCallingMode.DISABLED -> emptyList()
            ToolCallingMode.ENABLED -> tools.definitions()
            ToolCallingMode.AUTO -> {
                val capability = capabilityCache.computeIfAbsent(capabilityKey(config)) {
                    client.detectToolCapability(config)
                }
                if (capability.supportsToolCalling) {
                    tools.definitions()
                } else {
                    emitAutoModeNotice(session, capability, roundId)
                    emptyList()
                }
            }
        }
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
        session(pending.sessionId)?.let(::emitSnapshotChanged)
        pending.future.complete(approved)
    }

    private fun emitFailure(session: SessionState?, message: String, roundId: String? = currentRunRoundId) {
        val target = session ?: currentSession()
        val entry = TranscriptEntry.Error(
            id = nextId("error"),
            message = message,
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
        session.updatedAt = Instant.now()
        persistSessions()
    }

    private fun emitEntryAdded(session: SessionState, entry: TranscriptEntry) {
        emit(SessionEvent.EntryAdded(entry))
        emitSnapshotChanged(session)
    }

    private fun emitEntryUpdated(session: SessionState, entry: TranscriptEntry) {
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
        val session = SessionState(
            id = nextId("session"),
            title = title,
            transcript = mutableListOf(),
            stepGroups = emptyList(),
            history = mutableListOf(),
        )
        session.history += ConversationMessage.System(systemPrompt())
        session.transcript += TranscriptEntry.System(
            id = nextId("system"),
            message = "IDopen 已就绪。请先配置 OpenAI-compatible 接口，然后开始对话。",
        )
        sessions[session.id] = session
        return session
    }

    private fun currentSession(): SessionState = sessions.getValue(activeSessionId)

    private fun session(sessionId: String): SessionState? = sessions[sessionId]

    private fun snapshot(session: SessionState): ChatSessionSnapshot {
        return ChatSessionSnapshot(
            sessionId = session.id,
            title = session.title,
            updatedAt = session.updatedAt,
            running = currentRunSessionId == session.id && isRunning(),
            transcript = session.transcript.toList(),
            stepGroups = session.stepGroups.toList(),
        )
    }

    private fun emitSnapshotChanged(session: SessionState) {
        emit(SessionEvent.SessionSnapshotChanged(snapshot(session)))
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
                    transcript = session.transcript.toList(),
                    history = session.history.toList(),
                    updatedAt = session.updatedAt,
                    lastCapabilityNotice = session.lastCapabilityNotice,
                )
            },
        )
    }

    private fun systemPrompt(): String {
        val projectRoot = Paths.get(project.basePath ?: ".").toAbsolutePath().normalize()
        return """
            You are IDopen, a coding agent running inside IntelliJ IDEA.
            Work only inside the current project: $projectRoot
            Prefer reading files and searching the project before suggesting changes.
            When IDE context references are attached, treat them as hints and inspect exact code with IDE tools.
            Use read_file(path, offset, limit) for targeted reads instead of assuming full-file context.
            Use apply_patch_preview with edits for focused changes, or newContent for full-file rewrites.
            Use run_command only when it materially helps.
            Safe read-only commands may run without approval, mutating commands require approval, and dangerous commands are blocked.
            You are connected through an OpenAI-compatible chat completions API.
            The user may write in Chinese. Reply in the user's language.
        """.trimIndent()
    }

    private data class SessionState(
        val id: String,
        var title: String,
        val transcript: MutableList<TranscriptEntry>,
        var stepGroups: List<SessionStepGroup>,
        val history: MutableList<ConversationMessage>,
        var updatedAt: Instant = Instant.now(),
        var lastCapabilityNotice: String? = null,
    )

    private data class PendingApproval(
        val sessionId: String,
        val request: ApprovalRequest,
        val future: CompletableFuture<Boolean>,
    )
}
