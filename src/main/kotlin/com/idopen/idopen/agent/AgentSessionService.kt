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
    }

    private val client = OpenAICompatibleClient()
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val capabilityCache = ConcurrentHashMap<String, ToolCapability>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val idCounter = AtomicInteger()
    private val tools = IntelliJAgentTools(project, { IDopenSettingsState.getInstance() }, ::requestApproval)
    private val sessions = linkedMapOf<String, SessionState>()

    @Volatile
    private var activeSessionId: String

    @Volatile
    private var currentRun: Future<*>? = null

    @Volatile
    private var currentRunSessionId: String? = null

    init {
        val firstSession = createSessionInternal("新对话")
        activeSessionId = firstSession.id
        emit(SessionEvent.SessionsChanged(getSessions(), activeSessionId))
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

        if (session.title == "新对话") {
            session.title = summarizeTitle(userText)
        }

        if (attachments.isNotEmpty()) {
            val prepared = AttachmentPromptSupport.prepare(
                attachments = attachments,
                toolCallingEnabled = resolveToolCallingMode(settings) != ToolCallingMode.DISABLED,
            )
            val contextEntry = TranscriptEntry.Context(nextId("context"), prepared.transcriptSummary)
            appendEntry(session, contextEntry)
            session.history += ConversationMessage.System(prepared.injectedPrompt)
            emit(SessionEvent.EntryAdded(contextEntry))
        }

        val userEntry = TranscriptEntry.User(nextId("user"), userText)
        appendEntry(session, userEntry)
        session.history += ConversationMessage.User(userText)
        emit(SessionEvent.EntryAdded(userEntry))
        emitSessionsChanged()
        startRun(session.id)
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
        val session = createSessionInternal("新对话")
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

    private fun startRun(sessionId: String) {
        currentRunSessionId = sessionId
        currentRun = executor.submit {
            emit(SessionEvent.RunStateChanged(true))
            emitSessionsChanged()
            runCatching { agentLoop(sessionId) }
                .onFailure { emitFailure(session(sessionId), it.message ?: "未知错误") }
            emit(SessionEvent.RunStateChanged(false))
            currentRunSessionId = null
            emitSessionsChanged()
        }
    }

    private fun agentLoop(sessionId: String) {
        val session = session(sessionId) ?: return
        val settings = IDopenSettingsState.getInstance()
        val unlimitedUsage = settings.unlimitedUsage
        val provider = ProviderConfigSupport.fromSettings(settings)
        if (provider.error != null) {
            emitFailure(session, provider.error)
            return
        }

        val config = provider.config ?: return
        val toolDefinitions = resolveToolDefinitions(session, config, settings)

        var totalToolCalls = 0

        repeat(if (unlimitedUsage) Int.MAX_VALUE else MAX_AGENT_TURNS) {
            if (Thread.currentThread().isInterrupted) return

            var assistantEntry: TranscriptEntry.Assistant? = null
            val result = client.streamChat(
                OpenAICompatibleClient.ChatRequest(
                    providerConfig = config,
                    messages = session.history.toList(),
                    tools = toolDefinitions,
                ),
            ) { delta ->
                val entry = assistantEntry ?: TranscriptEntry.Assistant(nextId("assistant"), "").also {
                    assistantEntry = it
                    appendEntry(session, it)
                    emit(SessionEvent.EntryAdded(it))
                }
                entry.text += delta
                session.updatedAt = Instant.now()
                emit(SessionEvent.MessageDelta(entry.id, delta, entry.text))
            }

            if (assistantEntry == null && result.text.isNotBlank()) {
                val entry = TranscriptEntry.Assistant(nextId("assistant"), result.text)
                appendEntry(session, entry)
                assistantEntry = entry
                emit(SessionEvent.EntryAdded(entry))
            }

            session.history += ConversationMessage.Assistant(
                content = result.text,
                toolCalls = result.toolCalls,
            )

            if (result.toolCalls.isEmpty()) {
                emit(SessionEvent.RunCompleted("助手回复完成。"))
                return
            }

            if (toolDefinitions.isEmpty()) {
                emitFailure(session, "当前模型未启用工具调用，但返回了工具请求。")
                return
            }

            for (toolCall in result.toolCalls) {
                if (Thread.currentThread().isInterrupted) return
                totalToolCalls += 1
                if (!unlimitedUsage && totalToolCalls > MAX_TOOL_CALLS) {
                    emitFailure(session, "已达到工具调用安全上限（$MAX_TOOL_CALLS 次），任务已停止。")
                    return
                }

                val toolEntry = TranscriptEntry.ToolCall(
                    id = nextId("tool-call"),
                    toolName = toolCall.name,
                    argumentsJson = toolCall.argumentsJson,
                )
                appendEntry(session, toolEntry)
                emit(SessionEvent.EntryAdded(toolEntry))
                emit(SessionEvent.ToolRequested(toolCall.id, toolCall.name, toolCall.argumentsJson))

                val output = runCatching { tools.execute(toolCall) }
                    .getOrElse { ToolExecutionResult("工具执行失败：${it.message}", success = false) }

                val resultEntry = TranscriptEntry.ToolResult(
                    id = nextId("tool-result"),
                    toolName = toolCall.name,
                    output = output.content,
                    success = output.success,
                )
                appendEntry(session, resultEntry)
                session.history += ConversationMessage.Tool(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    content = output.content,
                )
                emit(SessionEvent.EntryAdded(resultEntry))
                emit(SessionEvent.ToolCompleted(toolCall.id, toolCall.name, output.content, output.success))
            }
        }

        if (!unlimitedUsage) {
            emitFailure(session, "已达到代理推理轮数上限（$MAX_AGENT_TURNS 轮），任务已停止。")
        }
    }

    private fun resolveToolDefinitions(
        session: SessionState,
        config: ProviderConfig,
        settings: IDopenSettingsState,
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
                    emitAutoModeNotice(session, capability)
                    emptyList()
                }
            }
        }
    }

    private fun emitAutoModeNotice(session: SessionState, capability: ToolCapability) {
        val detail = capability.detail?.takeIf { it.isNotBlank() } ?: "当前模型未通过工具调用探测。"
        if (session.lastCapabilityNotice == detail) return
        session.lastCapabilityNotice = detail
        val entry = TranscriptEntry.System(
            id = nextId("system"),
            message = "已自动退回纯聊天模式：$detail",
        )
        appendEntry(session, entry)
        emit(SessionEvent.EntryAdded(entry))
    }

    private fun requestApproval(request: ApprovalRequest): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        pendingApprovals[request.id] = future
        val entry = TranscriptEntry.Approval(nextId("approval-entry"), request)
        appendEntry(currentSession(), entry)
        emit(SessionEvent.EntryAdded(entry))
        emit(SessionEvent.ApprovalRequested(request))
        return future
    }

    private fun resolveApproval(callId: String, approved: Boolean) {
        pendingApprovals.remove(callId)?.complete(approved)
    }

    private fun emitFailure(session: SessionState?, message: String) {
        val target = session ?: currentSession()
        val entry = TranscriptEntry.Error(nextId("error"), message)
        appendEntry(target, entry)
        emit(SessionEvent.EntryAdded(entry))
        emit(SessionEvent.RunFailed(message))
    }

    private fun emit(event: SessionEvent) {
        listeners.forEach { it.onEvent(event) }
    }

    private fun emitSessionsChanged() {
        emit(SessionEvent.SessionsChanged(getSessions(), activeSessionId))
    }

    private fun appendEntry(session: SessionState, entry: TranscriptEntry) {
        session.transcript += entry
        session.updatedAt = Instant.now()
    }

    private fun createSessionInternal(title: String): SessionState {
        val session = SessionState(
            id = nextId("session"),
            title = title,
            transcript = mutableListOf(),
            history = mutableListOf(),
        )
        session.history += ConversationMessage.System(systemPrompt())
        session.transcript += TranscriptEntry.System(
            nextId("system"),
            "IDopen 已就绪。请先配置 OpenAI-compatible 接口，然后开始对话。",
        )
        sessions[session.id] = session
        return session
    }

    private fun currentSession(): SessionState = sessions.getValue(activeSessionId)

    private fun session(sessionId: String): SessionState? = sessions[sessionId]

    private fun summarizeTitle(text: String): String {
        return text
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .take(24)
            .ifBlank { "新对话" }
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
            Safe read-only commands may run without approval, but mutating commands and patches still require approval.
            You are connected through an OpenAI-compatible chat completions API.
            The user may write in Chinese. Reply in the user's language.
        """.trimIndent()
    }

    private data class SessionState(
        val id: String,
        var title: String,
        val transcript: MutableList<TranscriptEntry>,
        val history: MutableList<ConversationMessage>,
        var updatedAt: Instant = Instant.now(),
        var lastCapabilityNotice: String? = null,
    )
}
