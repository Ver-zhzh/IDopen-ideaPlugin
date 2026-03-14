package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

data class PersistedSessionState(
    val id: String,
    val title: String,
    val transcript: List<TranscriptEntry>,
    val history: List<ConversationMessage>,
    val updatedAt: Instant,
    val lastCapabilityNotice: String? = null,
)

data class RestoredSessions(
    val activeSessionId: String,
    val sessions: List<PersistedSessionState>,
)

object SessionPersistenceSupport {
    private val mapper = ObjectMapper()

    fun encode(activeSessionId: String, sessions: List<PersistedSessionState>): String {
        val root = mapper.createObjectNode()
        root.put("activeSessionId", activeSessionId)
        val sessionsNode = root.putArray("sessions")
        sessions.forEach { sessionsNode.add(serializeSession(it)) }
        return mapper.writeValueAsString(root)
    }

    fun decode(payloadJson: String): RestoredSessions {
        val root = mapper.readTree(payloadJson)
        val sessionsNode = root.path("sessions")
        val sessions = if (sessionsNode.isArray) sessionsNode.map(::deserializeSession) else emptyList()
        val activeSessionId = root.path("activeSessionId").asText("").takeIf { it.isNotBlank() }
            ?: sessions.firstOrNull()?.id
            ?: ""
        return RestoredSessions(activeSessionId, sessions)
    }

    fun highestGeneratedId(restored: RestoredSessions): Int {
        return restored.sessions
            .flatMap { session ->
                buildList {
                    add(session.id)
                    addAll(session.transcript.map { it.id })
                }
            }
            .mapNotNull(::extractSuffix)
            .maxOrNull()
            ?: 0
    }

    private fun serializeSession(session: PersistedSessionState): ObjectNode {
        return mapper.createObjectNode().apply {
            put("id", session.id)
            put("title", session.title)
            put("updatedAt", session.updatedAt.toEpochMilli())
            session.lastCapabilityNotice?.let { put("lastCapabilityNotice", it) }
            putArray("transcript").apply {
                session.transcript.forEach { add(serializeTranscriptEntry(it)) }
            }
            putArray("history").apply {
                session.history.forEach { add(serializeConversationMessage(it)) }
            }
        }
    }

    private fun deserializeSession(node: JsonNode): PersistedSessionState {
        val transcript = node.path("transcript").takeIf { it.isArray }?.map(::deserializeTranscriptEntry).orEmpty()
        val history = node.path("history").takeIf { it.isArray }?.map(::deserializeConversationMessage).orEmpty()
        return PersistedSessionState(
            id = node.path("id").asText(),
            title = node.path("title").asText("新对话"),
            transcript = transcript,
            history = history,
            updatedAt = Instant.ofEpochMilli(node.path("updatedAt").asLong(Instant.now().toEpochMilli())),
            lastCapabilityNotice = node.path("lastCapabilityNotice").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
        )
    }

    private fun serializeTranscriptEntry(entry: TranscriptEntry): ObjectNode {
        return mapper.createObjectNode().apply {
            put("id", entry.id)
            entry.roundId?.let { put("roundId", it) }
            when (entry) {
                is TranscriptEntry.User -> {
                    put("kind", "user")
                    put("text", entry.text)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.Assistant -> {
                    put("kind", "assistant")
                    put("text", entry.text)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.ToolCall -> {
                    put("kind", "toolCall")
                    put("toolName", entry.toolName)
                    put("argumentsJson", entry.argumentsJson)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.ToolResult -> {
                    put("kind", "toolResult")
                    put("toolName", entry.toolName)
                    put("output", entry.output)
                    put("success", entry.success)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.Approval -> {
                    put("kind", "approval")
                    put("createdAt", entry.createdAt.toEpochMilli())
                    set<ObjectNode>("request", serializeApproval(entry.request))
                }
                is TranscriptEntry.StepStart -> {
                    put("kind", "stepStart")
                    put("stepIndex", entry.stepIndex)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.StepFinish -> {
                    put("kind", "stepFinish")
                    put("stepIndex", entry.stepIndex)
                    put("reason", entry.reason)
                    put("toolCalls", entry.toolCalls)
                    put("success", entry.success)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.Error -> {
                    put("kind", "error")
                    put("message", entry.message)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.Context -> {
                    put("kind", "context")
                    put("summary", entry.summary)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
                is TranscriptEntry.System -> {
                    put("kind", "system")
                    put("message", entry.message)
                    put("createdAt", entry.createdAt.toEpochMilli())
                }
            }
        }
    }

    private fun deserializeTranscriptEntry(node: JsonNode): TranscriptEntry {
        val id = node.path("id").asText()
        val createdAt = Instant.ofEpochMilli(node.path("createdAt").asLong(Instant.now().toEpochMilli()))
        val roundId = node.path("roundId").takeIf { !it.isMissingNode && !it.isNull }?.asText()
        return when (node.path("kind").asText()) {
            "user" -> TranscriptEntry.User(id, node.path("text").asText(), createdAt, roundId)
            "assistant" -> TranscriptEntry.Assistant(id, node.path("text").asText(), createdAt, roundId)
            "toolCall" -> TranscriptEntry.ToolCall(id, node.path("toolName").asText(), node.path("argumentsJson").asText(), createdAt, roundId)
            "toolResult" -> TranscriptEntry.ToolResult(
                id = id,
                toolName = node.path("toolName").asText(),
                output = node.path("output").asText(),
                success = node.path("success").asBoolean(true),
                createdAt = createdAt,
                roundId = roundId,
            )
            "approval" -> TranscriptEntry.Approval(id, deserializeApproval(node.path("request")), createdAt, roundId)
            "stepStart" -> TranscriptEntry.StepStart(
                id = id,
                stepIndex = node.path("stepIndex").asInt(),
                createdAt = createdAt,
                roundId = roundId,
            )
            "stepFinish" -> TranscriptEntry.StepFinish(
                id = id,
                stepIndex = node.path("stepIndex").asInt(),
                reason = node.path("reason").asText(),
                toolCalls = node.path("toolCalls").asInt(),
                success = node.path("success").asBoolean(true),
                createdAt = createdAt,
                roundId = roundId,
            )
            "error" -> TranscriptEntry.Error(id, node.path("message").asText(), createdAt, roundId)
            "context" -> TranscriptEntry.Context(id, node.path("summary").asText(), createdAt, roundId)
            else -> TranscriptEntry.System(id, node.path("message").asText(), createdAt, roundId)
        }
    }

    private fun serializeConversationMessage(message: ConversationMessage): ObjectNode {
        return mapper.createObjectNode().apply {
            when (message) {
                is ConversationMessage.System -> {
                    put("kind", "system")
                    put("content", message.content)
                }
                is ConversationMessage.User -> {
                    put("kind", "user")
                    put("content", message.content)
                }
                is ConversationMessage.Assistant -> {
                    put("kind", "assistant")
                    put("content", message.content)
                    putArray("toolCalls").apply {
                        message.toolCalls.forEach { add(serializeToolCall(it)) }
                    }
                }
                is ConversationMessage.Tool -> {
                    put("kind", "tool")
                    put("toolCallId", message.toolCallId)
                    put("toolName", message.toolName)
                    put("content", message.content)
                }
            }
        }
    }

    private fun deserializeConversationMessage(node: JsonNode): ConversationMessage {
        return when (node.path("kind").asText()) {
            "system" -> ConversationMessage.System(node.path("content").asText())
            "user" -> ConversationMessage.User(node.path("content").asText())
            "assistant" -> ConversationMessage.Assistant(
                content = node.path("content").asText(),
                toolCalls = node.path("toolCalls").takeIf { it.isArray }?.map(::deserializeToolCall).orEmpty(),
            )
            else -> ConversationMessage.Tool(
                toolCallId = node.path("toolCallId").asText(),
                toolName = node.path("toolName").asText(),
                content = node.path("content").asText(),
            )
        }
    }

    private fun serializeToolCall(toolCall: ToolCall): ObjectNode {
        return mapper.createObjectNode().apply {
            put("id", toolCall.id)
            put("name", toolCall.name)
            put("argumentsJson", toolCall.argumentsJson)
        }
    }

    private fun deserializeToolCall(node: JsonNode): ToolCall {
        return ToolCall(
            id = node.path("id").asText(),
            name = node.path("name").asText(),
            argumentsJson = node.path("argumentsJson").asText(),
        )
    }

    private fun serializeApproval(request: ApprovalRequest): ObjectNode {
        return mapper.createObjectNode().apply {
            put("id", request.id)
            put("type", request.type.name)
            put("title", request.title)
            put("status", request.status.name)
            set<ObjectNode>("payload", serializeApprovalPayload(request.payload))
        }
    }

    private fun deserializeApproval(node: JsonNode): ApprovalRequest {
        return ApprovalRequest(
            id = node.path("id").asText(),
            type = ApprovalRequest.Type.valueOf(node.path("type").asText()),
            title = node.path("title").asText(),
            payload = deserializeApprovalPayload(node.path("payload")),
            status = node.path("status").asText().takeIf { it.isNotBlank() }?.let(ApprovalRequest.Status::valueOf)
                ?: ApprovalRequest.Status.PENDING,
        )
    }

    private fun serializeApprovalPayload(payload: ApprovalPayload): ObjectNode {
        return mapper.createObjectNode().apply {
            when (payload) {
                is ApprovalPayload.Command -> {
                    put("kind", "command")
                    put("command", payload.command)
                    put("workingDirectory", payload.workingDirectory)
                }
                is ApprovalPayload.Patch -> {
                    put("kind", "patch")
                    put("filePath", payload.filePath)
                    put("beforeText", payload.beforeText)
                    put("afterText", payload.afterText)
                    put("explanation", payload.explanation)
                }
            }
        }
    }

    private fun deserializeApprovalPayload(node: JsonNode): ApprovalPayload {
        return when (node.path("kind").asText()) {
            "command" -> ApprovalPayload.Command(
                command = node.path("command").asText(),
                workingDirectory = node.path("workingDirectory").asText(),
            )
            else -> ApprovalPayload.Patch(
                filePath = node.path("filePath").asText(),
                beforeText = node.path("beforeText").asText(),
                afterText = node.path("afterText").asText(),
                explanation = node.path("explanation").asText(),
            )
        }
    }

    private fun extractSuffix(id: String): Int? {
        return id.substringAfterLast('-', "").toIntOrNull()
    }
}
