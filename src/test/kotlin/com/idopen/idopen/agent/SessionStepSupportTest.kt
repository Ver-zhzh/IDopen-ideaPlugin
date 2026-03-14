package com.idopen.idopen.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionStepSupportTest {
    @Test
    fun `group combines pre-step context with the first step in a round`() {
        val now = Instant.parse("2026-03-14T08:00:00Z")
        val groups = SessionStepSupport.group(
            listOf(
                TranscriptEntry.Context("context-1", "current file", now, "round-1"),
                TranscriptEntry.User("user-1", "查看项目", now, "round-1"),
                TranscriptEntry.StepStart("step-start-1", 1, now, "round-1"),
                TranscriptEntry.Assistant("assistant-1", "我先看结构。", now, "round-1"),
                TranscriptEntry.ToolInvocation(
                    id = "tool-1",
                    callId = "call-1",
                    toolName = "read_project_tree",
                    argumentsJson = """{"maxDepth":2}""",
                    state = ToolInvocationState.COMPLETED,
                    output = "ok",
                    success = true,
                    createdAt = now,
                    roundId = "round-1",
                ),
                TranscriptEntry.StepFinish("step-finish-1", 1, "tool-loop", 1, true, now, "round-1"),
            ),
        )

        assertEquals(1, groups.size)
        val first = groups.first()
        assertEquals("round-1", first.roundId)
        assertEquals(1, first.stepIndex)
        assertEquals(1, first.contextEntries.size)
        assertEquals(1, first.userEntries.size)
        assertEquals(1, first.toolEntries.size)
        assertNotNull(first.started)
        assertNotNull(first.finished)
    }

    @Test
    fun `group splits multiple steps in the same round`() {
        val now = Instant.parse("2026-03-14T08:00:00Z")
        val groups = SessionStepSupport.group(
            listOf(
                TranscriptEntry.User("user-1", "继续", now, "round-1"),
                TranscriptEntry.StepStart("step-start-1", 1, now, "round-1"),
                TranscriptEntry.Assistant("assistant-1", "先读文件。", now, "round-1"),
                TranscriptEntry.StepFinish("step-finish-1", 1, "tool-loop", 1, true, now, "round-1"),
                TranscriptEntry.StepStart("step-start-2", 2, now, "round-1"),
                TranscriptEntry.Assistant("assistant-2", "再总结。", now, "round-1"),
                TranscriptEntry.StepFinish("step-finish-2", 2, "final", 0, true, now, "round-1"),
            ),
        )

        assertEquals(2, groups.size)
        assertEquals(1, groups[0].stepIndex)
        assertEquals(2, groups[1].stepIndex)
        assertEquals(1, groups[0].userEntries.size)
        assertEquals(0, groups[1].userEntries.size)
        assertEquals("tool-loop", groups[0].finished?.reason)
        assertEquals("final", groups[1].finished?.reason)
    }

    @Test
    fun `append produces the same groups as regrouping the full transcript`() {
        val now = Instant.parse("2026-03-14T08:00:00Z")
        val transcript = listOf(
            TranscriptEntry.Context("context-1", "selection", now, "round-1"),
            TranscriptEntry.User("user-1", "inspect this", now, "round-1"),
            TranscriptEntry.StepStart("step-start-1", 1, now, "round-1"),
            TranscriptEntry.Assistant("assistant-1", "reading files", now, "round-1"),
            TranscriptEntry.ToolInvocation(
                id = "tool-1",
                callId = "call-1",
                toolName = "read_file",
                argumentsJson = """{"path":"README.md"}""",
                state = ToolInvocationState.RUNNING,
                createdAt = now,
                roundId = "round-1",
            ),
            TranscriptEntry.StepFinish("step-finish-1", 1, "tool-loop", 1, true, now, "round-1"),
            TranscriptEntry.StepStart("step-start-2", 2, now, "round-1"),
            TranscriptEntry.Assistant("assistant-2", "done", now, "round-1"),
            TranscriptEntry.StepFinish("step-finish-2", 2, "final", 0, true, now, "round-1"),
        )

        val appended = transcript.fold(emptyList<SessionStepGroup>(), SessionStepSupport::append)
        val regrouped = SessionStepSupport.group(transcript)

        assertEquals(regrouped, appended)
    }

    @Test
    fun `group exposes status summary and tool metadata`() {
        val now = Instant.parse("2026-03-14T08:00:00Z")
        val group = SessionStepSupport.group(
            listOf(
                TranscriptEntry.User("user-1", "check project", now, "round-1"),
                TranscriptEntry.StepStart("step-start-1", 1, now, "round-1"),
                TranscriptEntry.Assistant("assistant-1", "I will inspect files", now, "round-1"),
                TranscriptEntry.ToolInvocation(
                    id = "tool-1",
                    callId = "call-1",
                    toolName = "read_project_tree",
                    argumentsJson = """{"maxDepth":2}""",
                    state = ToolInvocationState.COMPLETED,
                    createdAt = now,
                    roundId = "round-1",
                ),
                TranscriptEntry.Approval(
                    id = "approval-1",
                    request = ApprovalRequest(
                        id = "request-1",
                        type = ApprovalRequest.Type.COMMAND,
                        title = "run",
                        payload = ApprovalPayload.Command("dir", "D:/Project/IDopen"),
                        status = ApprovalRequest.Status.APPROVED,
                    ),
                    createdAt = now,
                    roundId = "round-1",
                ),
                TranscriptEntry.StepFinish("step-finish-1", 1, "final", 1, true, now, "round-1"),
            ),
        ).single()

        assertEquals(SessionStepStatus.COMPLETED, group.status)
        assertEquals(listOf("read_project_tree"), group.toolNames)
        assertEquals(listOf(ApprovalRequest.Type.COMMAND), group.approvalKinds)
        assertTrue(group.summary!!.contains("tools: read_project_tree"))
        assertTrue(group.summary!!.contains("approvals: command"))
    }
}
