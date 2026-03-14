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
)

data class PatchEdit(
    val search: String? = null,
    val replace: String? = null,
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
    ) : ConversationMessage

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
    ) : TranscriptEntry

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
