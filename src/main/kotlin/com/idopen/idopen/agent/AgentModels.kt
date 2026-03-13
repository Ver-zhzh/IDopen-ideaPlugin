package com.idopen.idopen.agent

import com.intellij.openapi.Disposable
import java.time.Instant

enum class ProviderType {
    OPENAI_COMPATIBLE,
}

data class ProviderConfig(
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val headers: Map<String, String>,
)

data class AttachmentContext(
    val label: String,
    val reference: String = label,
    val resolvedContent: String? = null,
    val content: String? = resolvedContent,
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

sealed interface ConversationMessage {
    data class System(val content: String) : ConversationMessage
    data class User(val content: String) : ConversationMessage
    data class Assistant(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : ConversationMessage

    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val content: String,
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

sealed interface TranscriptEntry {
    val id: String

    data class User(
        override val id: String,
        val text: String,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry

    data class Assistant(
        override val id: String,
        var text: String,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry

    data class ToolCall(
        override val id: String,
        val toolName: String,
        val argumentsJson: String,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry

    data class ToolResult(
        override val id: String,
        val toolName: String,
        val output: String,
        val success: Boolean,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry

    data class Approval(
        override val id: String,
        val request: ApprovalRequest,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry

    data class Error(
        override val id: String,
        val message: String,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry

    data class Context(
        override val id: String,
        val summary: String,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry

    data class System(
        override val id: String,
        val message: String,
        val createdAt: Instant = Instant.now(),
    ) : TranscriptEntry
}

sealed interface SessionEvent {
    data class EntryAdded(val entry: TranscriptEntry) : SessionEvent
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

fun interface SessionListener {
    fun onEvent(event: SessionEvent)
}

fun interface SessionSubscription : Disposable {
    override fun dispose()
}
