package com.idopen.idopen.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SessionPersistenceSupportTest {
    @Test
    fun `encode and decode round trip sessions`() {
        val now = Instant.parse("2026-03-14T08:00:00Z")
        val sessions = listOf(
            PersistedSessionState(
                id = "session-7",
                title = "查看项目",
                transcript = listOf(
                    TranscriptEntry.System("system-1", "ready", now),
                    TranscriptEntry.User("user-2", "你好", now, "round-1"),
                    TranscriptEntry.StepStart("step-start-10", 1, now, "round-1"),
                    TranscriptEntry.Assistant("assistant-3", "## 标题\n**内容**", now, "round-1"),
                    TranscriptEntry.ToolCall("tool-call-4", "read_file", """{"path":"README.md"}""", now, "round-1"),
                    TranscriptEntry.ToolResult("tool-result-5", "read_file", "ok", true, now, "round-1"),
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
                    ConversationMessage.User("你好"),
                    ConversationMessage.Assistant(
                        content = "已查看",
                        toolCalls = listOf(ToolCall("call-1", "read_file", """{"path":"README.md"}""")),
                    ),
                    ConversationMessage.Tool("call-1", "read_file", "README content"),
                ),
                updatedAt = now,
                lastCapabilityNotice = "fallback",
            ),
        )

        val encoded = SessionPersistenceSupport.encode("session-7", sessions)
        val decoded = SessionPersistenceSupport.decode(encoded)

        assertEquals("session-7", decoded.activeSessionId)
        assertEquals(1, decoded.sessions.size)
        assertEquals("查看项目", decoded.sessions.first().title)
        assertEquals("fallback", decoded.sessions.first().lastCapabilityNotice)
        assertIs<TranscriptEntry.StepStart>(decoded.sessions.first().transcript[2])
        assertIs<TranscriptEntry.Assistant>(decoded.sessions.first().transcript[3])
        assertEquals("round-1", decoded.sessions.first().transcript[3].roundId)
        val stepFinish = assertIs<TranscriptEntry.StepFinish>(decoded.sessions.first().transcript[6])
        assertEquals("tool-loop", stepFinish.reason)
        assertEquals(1, stepFinish.toolCalls)
        val approval = assertIs<TranscriptEntry.Approval>(decoded.sessions.first().transcript[7])
        assertEquals(ApprovalRequest.Status.APPROVED, approval.request.status)
        assertIs<ApprovalPayload.Command>(approval.request.payload)
        assertEquals("round-1", approval.roundId)
        val assistantHistory = assertIs<ConversationMessage.Assistant>(decoded.sessions.first().history[2])
        assertEquals(1, assistantHistory.toolCalls.size)
    }

    @Test
    fun `highest generated id uses transcript and session ids`() {
        val restored = RestoredSessions(
            activeSessionId = "session-2",
            sessions = listOf(
                PersistedSessionState(
                    id = "session-2",
                    title = "a",
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
}
