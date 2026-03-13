package com.idopen.idopen.agent

import com.idopen.idopen.settings.IDopenSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class AgentSessionService(private val project: Project) {
    private val client = OpenAICompatibleClient()
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val transcript = Collections.synchronizedList(mutableListOf<TranscriptEntry>())
    private val history = Collections.synchronizedList(mutableListOf<ConversationMessage>())
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val idCounter = AtomicInteger()
    private val tools = IntelliJAgentTools(project, { IDopenSettingsState.getInstance() }, ::requestApproval)

    @Volatile
    private var currentRun: Future<*>? = null

    init {
        history += ConversationMessage.System(systemPrompt())
        transcript += TranscriptEntry.System(
            nextId("system"),
            "IDopen 已就绪。请先配置 OpenAI-compatible 接口，然后开始对话。",
        )
    }

    fun sendUserMessage(text: String, attachments: List<AttachmentContext> = emptyList()) {
        if (text.isBlank()) return
        if (currentRun?.isDone == false) {
            emitFailure("当前已有任务在运行，请先停止后再发送新消息。")
            return
        }

        val rendered = buildString {
            appendLine(text.trim())
            if (attachments.isNotEmpty()) {
                appendLine()
                attachments.forEach { attachment ->
                    appendLine("### ${attachment.label}")
                    appendLine(attachment.content.trim())
                    appendLine()
                }
            }
        }.trim()

        val entry = TranscriptEntry.User(nextId("user"), rendered)
        transcript += entry
        history += ConversationMessage.User(rendered)
        emit(SessionEvent.EntryAdded(entry))
        startRun()
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

    fun getTranscript(): List<TranscriptEntry> = transcript.toList()

    private fun startRun() {
        currentRun = executor.submit {
            emit(SessionEvent.RunStateChanged(true))
            runCatching { agentLoop() }
                .onFailure { emitFailure(it.message ?: "未知错误") }
            emit(SessionEvent.RunStateChanged(false))
        }
    }

    private fun agentLoop() {
        val settings = IDopenSettingsState.getInstance()
        val provider = ProviderConfigSupport.fromSettings(settings)
        if (provider.error != null) {
            emitFailure(provider.error)
            return
        }
        val config = provider.config ?: return
        val toolDefinitions = if (settings.enableToolCalling) tools.definitions() else emptyList()

        repeat(8) {
            if (Thread.currentThread().isInterrupted) return

            val assistantEntry = TranscriptEntry.Assistant(nextId("assistant"), "")
            transcript += assistantEntry
            emit(SessionEvent.EntryAdded(assistantEntry))

            val result = client.streamChat(
                OpenAICompatibleClient.ChatRequest(
                    providerConfig = config,
                    messages = history.toList(),
                    tools = toolDefinitions,
                ),
            ) { delta ->
                assistantEntry.text += delta
                emit(SessionEvent.MessageDelta(assistantEntry.id, delta, assistantEntry.text))
            }

            history += ConversationMessage.Assistant(
                content = result.text,
                toolCalls = result.toolCalls,
            )

            if (result.toolCalls.isEmpty()) {
                emit(SessionEvent.RunCompleted("助手回复完成。"))
                return
            }

            if (!settings.enableToolCalling) {
                emitFailure("当前模型未启用工具调用，但返回了工具请求。")
                return
            }

            for (toolCall in result.toolCalls) {
                if (Thread.currentThread().isInterrupted) return
                val callEntry = TranscriptEntry.ToolCall(nextId("tool-call"), toolCall.name, toolCall.argumentsJson)
                transcript += callEntry
                emit(SessionEvent.EntryAdded(callEntry))
                emit(SessionEvent.ToolRequested(toolCall.id, toolCall.name, toolCall.argumentsJson))

                val output = runCatching { tools.execute(toolCall) }
                    .getOrElse { ToolExecutionResult("工具执行失败：${it.message}", success = false) }

                val resultEntry = TranscriptEntry.ToolResult(
                    id = nextId("tool-result"),
                    toolName = toolCall.name,
                    output = output.content,
                    success = output.success,
                )
                transcript += resultEntry
                history += ConversationMessage.Tool(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    content = output.content,
                )
                emit(SessionEvent.EntryAdded(resultEntry))
                emit(SessionEvent.ToolCompleted(toolCall.id, toolCall.name, output.content, output.success))
            }
        }

        emitFailure("已达到最大工具调用轮数，任务已停止。")
    }

    private fun requestApproval(request: ApprovalRequest): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        pendingApprovals[request.id] = future
        val entry = TranscriptEntry.Approval(nextId("approval-entry"), request)
        transcript += entry
        emit(SessionEvent.EntryAdded(entry))
        emit(SessionEvent.ApprovalRequested(request))
        return future
    }

    private fun resolveApproval(callId: String, approved: Boolean) {
        pendingApprovals.remove(callId)?.complete(approved)
    }

    private fun emitFailure(message: String) {
        val entry = TranscriptEntry.Error(nextId("error"), message)
        transcript += entry
        emit(SessionEvent.EntryAdded(entry))
        emit(SessionEvent.RunFailed(message))
    }

    private fun emit(event: SessionEvent) {
        listeners.forEach { it.onEvent(event) }
    }

    private fun nextId(prefix: String): String = "$prefix-${idCounter.incrementAndGet()}"

    private fun systemPrompt(): String {
        val projectRoot = Paths.get(project.basePath ?: ".").toAbsolutePath().normalize()
        return """
            You are IDopen, a coding agent running inside IntelliJ IDEA.
            Work only inside the current project: $projectRoot
            Prefer reading files and searching the project before suggesting changes.
            Use apply_patch_preview to modify files. newContent must be the full updated file contents.
            Use run_command only when it materially helps. Every command and patch requires user approval.
            You are connected through an OpenAI-compatible chat completions API.
            The user may write in Chinese. Reply in the user's language.
        """.trimIndent()
    }
}
