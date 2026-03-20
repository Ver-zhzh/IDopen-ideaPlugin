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
            todos = emptyList(),
            transcript = emptyList(),
            stepGroups = emptyList(),
            steps = emptyList(),
        )

        val plan = AgentPlanningSupport.buildPlan(
            snapshot = snapshot,
            roundId = "round-1",
            userRequest = "Search the project, fix the bug, and run the tests",
            availableTools = listOf(
                ToolDefinition("todo_read", "", emptyMap()),
                ToolDefinition("todo_write", "", emptyMap()),
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
        assertTrue(plan.recommendedTools.contains("todo_write"))
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
            parts = listOf(
                SessionStepPart.ToolResult(
                    callId = "call-1",
                    toolName = "read_file",
                    state = ToolInvocationState.ERROR,
                    metadata = emptyMap(),
                    output = "not found",
                    success = false,
                    recoveryHint = "Re-check the path, then inspect the project tree before retrying read_file.",
                    createdAt = Instant.parse("2026-03-14T08:00:01Z"),
                    finishedAt = Instant.parse("2026-03-14T08:00:02Z"),
                ),
            ),
        )
        val snapshot = ChatSessionSnapshot(
            sessionId = "session-1",
            title = "test",
            updatedAt = Instant.parse("2026-03-14T08:00:03Z"),
            running = false,
            todos = emptyList(),
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

        assertEquals(true, plan.recoveryHint?.contains("suggests"))
        assertEquals(true, plan.recoveryHint?.contains("project tree"))
    }

    @Test
    fun `plan recommends skill tool for workflow oriented requests`() {
        val snapshot = ChatSessionSnapshot(
            sessionId = "session-1",
            title = "test",
            updatedAt = Instant.parse("2026-03-14T08:00:00Z"),
            running = false,
            todos = emptyList(),
            transcript = emptyList(),
            stepGroups = emptyList(),
            steps = emptyList(),
        )

        val plan = AgentPlanningSupport.buildPlan(
            snapshot = snapshot,
            roundId = "round-2",
            userRequest = "Follow the release workflow and use the right skill before editing files",
            availableTools = listOf(
                ToolDefinition("skill", "", emptyMap()),
                ToolDefinition("read_file", "", emptyMap()),
            ),
            runtimeProfile = ProviderRuntimeProfile(
                config = ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://example.com/v1", "key", "qwen", emptyMap()),
                supportsToolCalling = true,
                effectiveToolMode = ToolCallingMode.ENABLED,
                includeTools = true,
            ),
        )

        assertTrue(plan.recommendedTools.contains("skill"))
        assertTrue(plan.planningNotes.any { it.contains("project skills") })
    }

    @Test
    fun `plan recommends MCP inspection for browser and docs requests`() {
        val snapshot = ChatSessionSnapshot(
            sessionId = "session-1",
            title = "test",
            updatedAt = Instant.parse("2026-03-14T08:00:00Z"),
            running = false,
            todos = emptyList(),
            transcript = emptyList(),
            stepGroups = emptyList(),
            steps = emptyList(),
        )

        val plan = AgentPlanningSupport.buildPlan(
            snapshot = snapshot,
            roundId = "round-3",
            userRequest = "Use playwright MCP to inspect the docs site before making changes",
            availableTools = listOf(
                ToolDefinition("mcp_list_servers", "", emptyMap()),
                ToolDefinition("mcp_describe_server", "", emptyMap()),
                ToolDefinition("mcp_list_tools", "", emptyMap()),
                ToolDefinition("read_file", "", emptyMap()),
            ),
            runtimeProfile = ProviderRuntimeProfile(
                config = ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://example.com/v1", "key", "qwen", emptyMap()),
                supportsToolCalling = true,
                effectiveToolMode = ToolCallingMode.ENABLED,
                includeTools = true,
            ),
        )

        assertTrue(plan.recommendedTools.contains("mcp_list_servers"))
        assertTrue(plan.recommendedTools.contains("mcp_describe_server"))
        assertTrue(plan.recommendedTools.contains("mcp_list_tools"))
        assertTrue(plan.planningNotes.any { it.contains("MCP servers") })
        assertTrue(plan.planningNotes.any { it.contains("supported MCP server") })
    }

    @Test
    fun `plan recommends MCP resources and prompts for template driven context requests`() {
        val snapshot = ChatSessionSnapshot(
            sessionId = "session-1",
            title = "test",
            updatedAt = Instant.parse("2026-03-14T08:00:00Z"),
            running = false,
            todos = emptyList(),
            transcript = emptyList(),
            stepGroups = emptyList(),
            steps = emptyList(),
        )

        val plan = AgentPlanningSupport.buildPlan(
            snapshot = snapshot,
            roundId = "round-4",
            userRequest = "Use MCP prompt templates and docs resources as context before editing",
            availableTools = listOf(
                ToolDefinition("mcp_list_resources", "", emptyMap()),
                ToolDefinition("mcp_read_resource", "", emptyMap()),
                ToolDefinition("mcp_list_resource_templates", "", emptyMap()),
                ToolDefinition("mcp_list_prompts", "", emptyMap()),
                ToolDefinition("mcp_get_prompt", "", emptyMap()),
            ),
            runtimeProfile = ProviderRuntimeProfile(
                config = ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://example.com/v1", "key", "qwen", emptyMap()),
                supportsToolCalling = true,
                effectiveToolMode = ToolCallingMode.ENABLED,
                includeTools = true,
            ),
        )

        assertTrue(plan.recommendedTools.contains("mcp_list_resources"))
        assertTrue(plan.recommendedTools.contains("mcp_read_resource"))
        assertTrue(plan.recommendedTools.contains("mcp_list_resource_templates"))
        assertTrue(plan.recommendedTools.contains("mcp_list_prompts"))
        assertTrue(plan.recommendedTools.contains("mcp_get_prompt"))
        assertTrue(plan.planningNotes.any { it.contains("reference context") })
        assertTrue(plan.planningNotes.any { it.contains("prompt templates") })
    }
}
