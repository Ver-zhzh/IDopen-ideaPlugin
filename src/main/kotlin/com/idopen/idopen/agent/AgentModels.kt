package com.idopen.idopen.agent

import com.intellij.openapi.Disposable
import java.time.Instant

enum class ProviderType {
    OPENAI_COMPATIBLE,
}

enum class ToolCallingMode {
    AUTO,
    ENABLED,
    DISABLED,
    ;

    companion object {
        fun fromStored(value: String?): ToolCallingMode {
            return entries.firstOrNull { it.name == value } ?: AUTO
        }
    }
}

enum class AttachmentKind {
    CURRENT_FILE,
    CURRENT_SELECTION,
    GENERIC,
}

data class ProviderConfig(
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val headers: Map<String, String>,
)

data class AttachmentContext(
    val kind: AttachmentKind = AttachmentKind.GENERIC,
    val label: String,
    val reference: String = label,
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val resolvedContent: String? = null,
    val content: String? = resolvedContent,
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ToolProgressUpdate(
    val state: ToolInvocationState,
    val title: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ToolCapability(
    val supportsToolCalling: Boolean,
    val checkedAt: Instant = Instant.now(),
    val detail: String? = null,
)

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Instant,
    val entryCount: Int,
    val running: Boolean,
)

data class ChatSessionSnapshot(
    val sessionId: String,
    val title: String,
    val updatedAt: Instant,
    val running: Boolean,
    val transcript: List<TranscriptEntry>,
    val stepGroups: List<SessionStepGroup>,
    val steps: List<SessionStep>,
)

data class PatchEdit(
    val search: String? = null,
    val replace: String? = null,
    val occurrence: Int? = null,
    val replaceAll: Boolean = false,
    val before: String? = null,
    val after: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val newText: String? = null,
)

sealed interface ConversationMessage {
    val roundId: String?

    data class System(
        val content: String,
        override val roundId: String? = null,
    ) : ConversationMessage

    data class User(
        val content: String,
        override val roundId: String? = null,
    ) : ConversationMessage

    data class Assistant(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        override val roundId: String? = null,
        val outputParts: List<AssistantOutputPart> = emptyList(),
    ) : ConversationMessage {
        constructor(
            content: String,
            toolCalls: List<ToolCall>,
            roundId: String?,
        ) : this(content, toolCalls, roundId, emptyList())

        @Suppress("UNUSED_PARAMETER")
        constructor(
            content: String,
            toolCalls: List<ToolCall>,
            roundId: String?,
            mask: Int,
            marker: kotlin.jvm.internal.DefaultConstructorMarker?,
        ) : this(content, toolCalls, roundId, emptyList())
    }

    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val content: String,
        override val roundId: String? = null,
    ) : ConversationMessage
}

sealed interface ApprovalPayload {
    data class Command(
        val command: String,
        val workingDirectory: String,
    ) : ApprovalPayload

    data class Patch(
        val filePath: String,
        val beforeText: String,
        val afterText: String,
        val explanation: String,
    ) : ApprovalPayload
}

data class ApprovalRequest(
    val id: String,
    val type: Type,
    val title: String,
    val payload: ApprovalPayload,
    var status: Status = Status.PENDING,
) {
    enum class Type {
        COMMAND,
        PATCH,
    }

    enum class Status {
        PENDING,
        APPROVED,
        REJECTED,
    }
}

enum class ToolInvocationState {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR,
}

data class SessionStep(
    val roundId: String,
    val stepIndex: Int?,
    val status: SessionStepStatus,
    val title: String,
    val summary: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val reason: String?,
    val toolCalls: Int,
    val parts: List<SessionStepPart>,
)

sealed interface AssistantOutputPart {
    data class Text(
        val text: String,
    ) : AssistantOutputPart

    data class CodeBlock(
        val code: String,
        val language: String? = null,
    ) : AssistantOutputPart

    data class ListBlock(
        val items: List<String>,
        val ordered: Boolean,
    ) : AssistantOutputPart
}

sealed interface SessionStepPart {
    data class Context(
        val summary: String,
        val createdAt: Instant,
    ) : SessionStepPart

    data class User(
        val text: String,
        val createdAt: Instant,
    ) : SessionStepPart

    data class AssistantResponse(
        val text: String,
        val outputParts: List<AssistantOutputPart>,
        val createdAt: Instant,
    ) : SessionStepPart

    data class ToolCall(
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
        val title: String?,
        val metadata: Map<String, String>,
        val createdAt: Instant,
        val startedAt: Instant?,
    ) : SessionStepPart

    data class ToolResult(
        val callId: String,
        val toolName: String,
        val state: ToolInvocationState,
        val metadata: Map<String, String>,
        val output: String?,
        val success: Boolean?,
        val createdAt: Instant,
        val finishedAt: Instant?,
    ) : SessionStepPart

    data class ApprovalRequestPart(
        val requestId: String,
        val title: String,
        val type: ApprovalRequest.Type,
        val summary: String,
        val payload: ApprovalPayload,
        val createdAt: Instant,
    ) : SessionStepPart

    data class ApprovalDecision(
        val requestId: String,
        val title: String,
        val type: ApprovalRequest.Type,
        val status: ApprovalRequest.Status,
        val createdAt: Instant,
    ) : SessionStepPart

    data class Error(
        val message: String,
        val createdAt: Instant,
    ) : SessionStepPart

    data class System(
        val message: String,
        val createdAt: Instant,
    ) : SessionStepPart
}

sealed interface TranscriptEntry {
    val id: String
    val roundId: String?

    data class User(
        override val id: String,
        val text: String,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class Assistant(
        override val id: String,
        var text: String,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
        var outputParts: List<AssistantOutputPart> = emptyList(),
    ) : TranscriptEntry {
        constructor(
            id: String,
            text: String,
            createdAt: Instant,
            roundId: String?,
        ) : this(id, text, createdAt, roundId, emptyList())

        @Suppress("UNUSED_PARAMETER")
        constructor(
            id: String,
            text: String,
            createdAt: Instant,
            roundId: String?,
            mask: Int,
            marker: kotlin.jvm.internal.DefaultConstructorMarker?,
        ) : this(id, text, createdAt, roundId, emptyList())
    }

    data class ToolCall(
        override val id: String,
        val toolName: String,
        val argumentsJson: String,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class ToolResult(
        override val id: String,
        val toolName: String,
        val output: String,
        val success: Boolean,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class ToolInvocation(
        override val id: String,
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
        var state: ToolInvocationState = ToolInvocationState.PENDING,
        var title: String? = null,
        var metadata: Map<String, String> = emptyMap(),
        var startedAt: Instant? = null,
        var finishedAt: Instant? = null,
        var output: String? = null,
        var success: Boolean? = null,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class Approval(
        override val id: String,
        val request: ApprovalRequest,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class StepStart(
        override val id: String,
        val stepIndex: Int,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class StepFinish(
        override val id: String,
        val stepIndex: Int,
        val reason: String,
        val toolCalls: Int,
        val success: Boolean,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class Error(
        override val id: String,
        val message: String,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class Context(
        override val id: String,
        val summary: String,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry

    data class System(
        override val id: String,
        val message: String,
        val createdAt: Instant = Instant.now(),
        override val roundId: String? = null,
    ) : TranscriptEntry
}

sealed interface SessionEvent {
    data class SessionsChanged(
        val summaries: List<ChatSessionSummary>,
        val activeSessionId: String,
    ) : SessionEvent

    data class SessionSnapshotChanged(val snapshot: ChatSessionSnapshot) : SessionEvent

    data class EntryAdded(val entry: TranscriptEntry) : SessionEvent
    data class EntryUpdated(val entry: TranscriptEntry) : SessionEvent
    data class MessageDelta(
        val messageId: String,
        val delta: String,
        val snapshot: String,
        val outputParts: List<AssistantOutputPart> = emptyList(),
    ) : SessionEvent

    data class ToolRequested(
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
    ) : SessionEvent

    data class ApprovalRequested(val request: ApprovalRequest) : SessionEvent
    data class ToolCompleted(
        val callId: String,
        val toolName: String,
        val output: String,
        val success: Boolean,
    ) : SessionEvent

    data class RunStateChanged(val running: Boolean) : SessionEvent
    data class RunCompleted(val summary: String) : SessionEvent
    data class RunFailed(val message: String) : SessionEvent
}

data class ToolDefinition(
    val id: String,
    val description: String,
    val inputSchema: Map<String, Any>,
)

data class ToolExecutionResult(
    val content: String,
    val success: Boolean = true,
    val recoveryHint: String? = null,
)

fun interface ToolExecutionObserver {
    fun onUpdate(update: ToolProgressUpdate)
}

fun interface SessionListener {
    fun onEvent(event: SessionEvent)
}

fun interface SessionSubscription : Disposable {
    override fun dispose()
}
