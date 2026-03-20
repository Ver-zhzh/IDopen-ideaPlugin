package com.idopen.idopen.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionPersistenceSupportTest {
    @Test
    fun `encode and decode round trip sessions`() {
        val now = Instant.parse("2026-03-14T08:00:00Z")
        val sessions = listOf(
            PersistedSessionState(
                id = "session-7",
                title = "查看项目",
                todos = listOf(
                    SessionTodoItem("Inspect project structure", SessionTodoStatus.IN_PROGRESS),
                    SessionTodoItem("Summarize findings", SessionTodoStatus.PENDING),
                ),
                transcript = listOf(
                    TranscriptEntry.System("system-1", "ready", now),
                    TranscriptEntry.User("user-2", "你好", now, "round-1"),
                    TranscriptEntry.StepStart("step-start-10", 1, now, "round-1"),
                    TranscriptEntry.Assistant("assistant-3", "## 标题\n**内容**", now, "round-1"),
                    TranscriptEntry.ToolInvocation(
                        id = "tool-4",
                        callId = "call-1",
                        toolName = "read_file",
                        argumentsJson = """{"path":"README.md"}""",
                        state = ToolInvocationState.COMPLETED,
                        title = "读取文件",
                        metadata = mapOf("path" to "README.md"),
                        startedAt = now,
                        finishedAt = now.plusSeconds(1),
                        output = "ok",
                        success = true,
                        recoveryHint = "Retry with a narrower file path.",
                        createdAt = now,
                        roundId = "round-1",
                    ),
                    TranscriptEntry.StepFinish("step-finish-11", 1, "tool-loop", 1, true, now, "round-1"),
                    TranscriptEntry.Approval(
                        "approval-entry-6",
                        ApprovalRequest(
                            id = "approval-9",
                            type = ApprovalRequest.Type.COMMAND,
                            title = "执行命令",
                            payload = ApprovalPayload.Command("dir", "D:/Project/IDopen"),
                            status = ApprovalRequest.Status.APPROVED,
                        ),
                        now,
                        "round-1",
                    ),
                ),
                history = listOf(
                    ConversationMessage.System("system prompt"),
                    ConversationMessage.User("你好", "round-1"),
                    ConversationMessage.Assistant(
                        content = "已查看",
                        toolCalls = listOf(ToolCall("call-1", "read_file", """{"path":"README.md"}""")),
                        roundId = "round-1",
                    ),
                    ConversationMessage.Tool("call-1", "read_file", "README content", "round-1"),
                ),
                updatedAt = now,
                lastCapabilityNotice = "fallback",
                activeProjectAgent = "review/security",
            ),
        )

        val encoded = SessionPersistenceSupport.encode("session-7", sessions)
        val decoded = SessionPersistenceSupport.decode(encoded)

        assertEquals("session-7", decoded.activeSessionId)
        assertEquals(1, decoded.sessions.size)
        assertEquals("查看项目", decoded.sessions.first().title)
        assertEquals(2, decoded.sessions.first().todos.size)
        assertEquals(SessionTodoStatus.IN_PROGRESS, decoded.sessions.first().todos.first().status)
        assertEquals("fallback", decoded.sessions.first().lastCapabilityNotice)
        assertEquals("review/security", decoded.sessions.first().activeProjectAgent)
        assertIs<TranscriptEntry.StepStart>(decoded.sessions.first().transcript[2])
        assertIs<TranscriptEntry.Assistant>(decoded.sessions.first().transcript[3])
        assertEquals("round-1", decoded.sessions.first().transcript[3].roundId)
        val toolInvocation = assertIs<TranscriptEntry.ToolInvocation>(decoded.sessions.first().transcript[4])
        assertEquals(ToolInvocationState.COMPLETED, toolInvocation.state)
        assertEquals("读取文件", toolInvocation.title)
        assertEquals("README.md", toolInvocation.metadata["path"])
        assertEquals(now, toolInvocation.startedAt)
        assertEquals(now.plusSeconds(1), toolInvocation.finishedAt)
        assertEquals("ok", toolInvocation.output)
        assertEquals("Retry with a narrower file path.", toolInvocation.recoveryHint)
        val stepFinish = assertIs<TranscriptEntry.StepFinish>(decoded.sessions.first().transcript[5])
        assertEquals("tool-loop", stepFinish.reason)
        assertEquals(1, stepFinish.toolCalls)
        val approval = assertIs<TranscriptEntry.Approval>(decoded.sessions.first().transcript[6])
        assertEquals(ApprovalRequest.Status.APPROVED, approval.request.status)
        assertIs<ApprovalPayload.Command>(approval.request.payload)
        assertEquals("round-1", approval.roundId)
        val assistantHistory = assertIs<ConversationMessage.Assistant>(decoded.sessions.first().history[2])
        assertEquals(1, assistantHistory.toolCalls.size)
        assertEquals("round-1", assistantHistory.roundId)
        val toolHistory = assertIs<ConversationMessage.Tool>(decoded.sessions.first().history[3])
        assertEquals("round-1", toolHistory.roundId)
    }

    @Test
    fun `highest generated id uses transcript and session ids`() {
        val restored = RestoredSessions(
            activeSessionId = "session-2",
            sessions = listOf(
                PersistedSessionState(
                    id = "session-2",
                    title = "a",
                    todos = emptyList(),
                    transcript = listOf(
                        TranscriptEntry.System("system-4", "ready"),
                        TranscriptEntry.User("user-12", "hello"),
                    ),
                    history = listOf(ConversationMessage.System("prompt")),
                    updatedAt = Instant.now(),
                ),
            ),
        )

        val maxId = SessionPersistenceSupport.highestGeneratedId(restored)

        assertEquals(12, maxId)
    }

    @Test
    fun `decode falls back to first session when active id missing`() {
        val encoded = """
            {"sessions":[{"id":"session-1","title":"test","updatedAt":1710000000000,"transcript":[],"history":[]}]}
        """.trimIndent()

        val decoded = SessionPersistenceSupport.decode(encoded)

        assertEquals("session-1", decoded.activeSessionId)
        assertNotNull(decoded.sessions.firstOrNull())
    }

    @Test
    fun `decode keeps legacy tool call and tool result entries readable`() {
        val encoded = """
            {
              "activeSessionId":"session-1",
              "sessions":[
                {
                  "id":"session-1",
                  "title":"legacy",
                  "updatedAt":1710000000000,
                  "transcript":[
                    {"id":"tool-call-1","kind":"toolCall","toolName":"read_file","argumentsJson":"{\"path\":\"README.md\"}","createdAt":1710000000000,"roundId":"round-1"},
                    {"id":"tool-result-2","kind":"toolResult","toolName":"read_file","output":"ok","success":true,"createdAt":1710000001000,"roundId":"round-1"}
                  ],
                  "history":[]
                }
              ]
            }
        """.trimIndent()

        val decoded = SessionPersistenceSupport.decode(encoded)

        assertIs<TranscriptEntry.ToolCall>(decoded.sessions.first().transcript[0])
        assertIs<TranscriptEntry.ToolResult>(decoded.sessions.first().transcript[1])
    }

    @Test
    fun `assistant output parts survive persistence round trip`() {
        val now = Instant.parse("2026-03-14T08:00:00Z")
        val restored = SessionPersistenceSupport.decode(
            SessionPersistenceSupport.encode(
                activeSessionId = "session-1",
                sessions = listOf(
                    PersistedSessionState(
                        id = "session-1",
                        title = "parts",
                        todos = listOf(SessionTodoItem("Preserve reasoning trace", SessionTodoStatus.PENDING)),
                        transcript = listOf(
                            TranscriptEntry.Assistant(
                                id = "assistant-1",
                                text = "summary\n```kotlin\nprintln(1)\n```",
                                createdAt = now,
                                roundId = "round-1",
                                outputParts = listOf(
                                    AssistantOutputPart.Text("summary"),
                                    AssistantOutputPart.CodeBlock("println(1)", "kotlin"),
                                ),
                            ),
                        ),
                        history = listOf(
                            ConversationMessage.System("prompt"),
                            ConversationMessage.Assistant(
                                content = "summary",
                                roundId = "round-1",
                                outputParts = listOf(AssistantOutputPart.Text("summary")),
                                responseItems = listOf("""{"type":"reasoning","id":"rs_1","summary":[]}"""),
                            ),
                        ),
                        updatedAt = now,
                    ),
                ),
            ),
        )

        val assistantEntry = assertIs<TranscriptEntry.Assistant>(restored.sessions.first().transcript.single())
        assertEquals(2, assistantEntry.outputParts.size)
        assertIs<AssistantOutputPart.CodeBlock>(assistantEntry.outputParts.last())
        val assistantHistory = assertIs<ConversationMessage.Assistant>(restored.sessions.first().history.last())
        assertEquals(1, assistantHistory.outputParts.size)
        assertEquals(1, assistantHistory.responseItems.size)
        assertTrue(assistantHistory.responseItems.first().contains("\"type\":\"reasoning\""))
    }
}
