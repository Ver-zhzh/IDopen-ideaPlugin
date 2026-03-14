package com.idopen.idopen.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentPlanningSupportTest {
    @Test
    fun `plan recommends tools and subtasks for multi-step code requests`() {
        val snapshot = ChatSessionSnapshot(
            sessionId = "session-1",
            title = "test",
            updatedAt = Instant.parse("2026-03-14T08:00:00Z"),
            running = false,
            transcript = emptyList(),
            stepGroups = emptyList(),
            steps = emptyList(),
        )

        val plan = AgentPlanningSupport.buildPlan(
            snapshot = snapshot,
            roundId = "round-1",
            userRequest = "搜索这个调用链，然后修复这个 bug，并运行测试",
            availableTools = listOf(
                ToolDefinition("search_text", "", emptyMap()),
                ToolDefinition("read_file", "", emptyMap()),
                ToolDefinition("apply_patch_preview", "", emptyMap()),
                ToolDefinition("run_command", "", emptyMap()),
            ),
            runtimeProfile = ProviderRuntimeProfile(
                config = ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://example.com/v1", "key", "qwen", emptyMap()),
                supportsToolCalling = true,
                effectiveToolMode = ToolCallingMode.ENABLED,
                includeTools = true,
            ),
        )

        assertTrue(plan.subtasks.isNotEmpty())
        assertTrue(plan.recommendedTools.contains("search_text"))
        assertTrue(plan.recommendedTools.contains("apply_patch_preview"))
        assertTrue(plan.recommendedTools.contains("run_command"))
    }

    @Test
    fun `plan includes recovery hint from previous failed step`() {
        val failedStep = SessionStep(
            roundId = "round-0",
            stepIndex = 1,
            status = SessionStepStatus.FAILED,
            title = "failed",
            summary = "Step 1 [failed] user: read config | tools: read_file",
            startedAt = Instant.parse("2026-03-14T08:00:00Z"),
            finishedAt = Instant.parse("2026-03-14T08:00:02Z"),
            reason = "tool-loop",
            toolCalls = 1,
            parts = emptyList(),
        )
        val snapshot = ChatSessionSnapshot(
            sessionId = "session-1",
            title = "test",
            updatedAt = Instant.parse("2026-03-14T08:00:03Z"),
            running = false,
            transcript = emptyList(),
            stepGroups = emptyList(),
            steps = listOf(failedStep),
        )

        val plan = AgentPlanningSupport.buildPlan(
            snapshot = snapshot,
            roundId = "round-1",
            userRequest = "continue",
            availableTools = emptyList(),
            runtimeProfile = ProviderRuntimeProfile(
                config = ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://example.com/v1", "key", "qwen", emptyMap()),
                supportsToolCalling = false,
                effectiveToolMode = ToolCallingMode.DISABLED,
                includeTools = false,
            ),
        )

        assertEquals(true, plan.recoveryHint?.contains("previous failed step"))
    }
}
