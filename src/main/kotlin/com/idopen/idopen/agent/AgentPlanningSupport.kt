package com.idopen.idopen.agent

data class AgentTurnPlan(
    val subtasks: List<String>,
    val recommendedTools: List<String>,
    val recoveryHint: String? = null,
    val planningNotes: List<String> = emptyList(),
) {
    fun asSystemMessages(roundId: String): List<ConversationMessage.System> {
        val sections = buildList {
            if (subtasks.isNotEmpty()) {
                add(
                    buildString {
                        appendLine("Execution plan:")
                        subtasks.forEachIndexed { index, task ->
                            append(index + 1)
                            append(". ")
                            appendLine(task)
                        }
                    }.trim(),
                )
            }
            if (recommendedTools.isNotEmpty()) {
                add("Recommended tools: ${recommendedTools.joinToString(", ")}")
            }
            recoveryHint?.takeIf { it.isNotBlank() }?.let { add("Recovery hint: $it") }
            if (planningNotes.isNotEmpty()) {
                add(
                    buildString {
                        appendLine("Planning notes:")
                        planningNotes.forEach { note ->
                            append("- ")
                            appendLine(note)
                        }
                    }.trim(),
                )
            }
        }
        return sections.map { ConversationMessage.System(it, roundId) }
    }
}

object AgentPlanningSupport {
    fun buildPlan(
        snapshot: ChatSessionSnapshot,
        roundId: String,
        userRequest: String,
        availableTools: List<ToolDefinition>,
        runtimeProfile: ProviderRuntimeProfile,
    ): AgentTurnPlan {
        val subtasks = splitSubtasks(userRequest)
        val recommendedTools = recommendTools(userRequest, availableTools)
        val lastFailedStep = snapshot.steps
            .filter { it.roundId != roundId }
            .lastOrNull { it.status == SessionStepStatus.FAILED }
        val recoveryHint = lastFailedStep?.let(::extractRecoveryHint)
        val planningNotes = buildList {
            if (!runtimeProfile.includeTools) {
                add("Tool calling is unavailable for the current model, so prefer direct analysis over tool-dependent plans.")
            }
            if (subtasks.size >= 2 && "todo_write" in recommendedTools) {
                add("Use todo_write to keep a short ordered checklist when the request spans multiple concrete steps.")
            }
            if (snapshot.todos.isNotEmpty() && "todo_read" in recommendedTools) {
                add("Read the current todo list before continuing so unfinished work stays aligned with the session plan.")
            }
            if ("skill" in recommendedTools) {
                add("If one of the available project skills matches the request, load it before inventing a new workflow.")
            }
            if ("mcp_list_servers" in recommendedTools) {
                add("Inspect configured MCP servers before assuming browser, database, docs, or external context integrations are available.")
            }
            if ("mcp_list_tools" in recommendedTools) {
                add("After choosing a supported MCP server, list its tools before calling one so the workflow stays grounded in the server's actual schema.")
            }
            if ("mcp_list_resources" in recommendedTools) {
                add("If the request depends on external docs or reference context, inspect MCP resources before guessing what the server exposes.")
            }
            if ("mcp_list_prompts" in recommendedTools) {
                add("If the request depends on MCP prompt templates, list prompts first so names and required arguments come from the server instead of guesswork.")
            }
            if (looksBroadRequest(userRequest) && recommendedTools.isEmpty()) {
                add("Start with a narrow project inspection before proposing code changes.")
            }
            if (userRequest.contains("fix", ignoreCase = true)) {
                add("Read the exact file or selection before editing, and prefer focused edits over full-file rewrites.")
            }
            if (userRequest.contains("search", ignoreCase = true)) {
                add("Search the project first, then read only the files that look relevant.")
            }
        }
        return AgentTurnPlan(
            subtasks = subtasks,
            recommendedTools = recommendedTools,
            recoveryHint = recoveryHint ?: lastFailedStep?.summary?.let {
                "The previous failed step was: $it. Avoid repeating the same action unchanged."
            },
            planningNotes = planningNotes,
        )
    }

    private fun extractRecoveryHint(step: SessionStep): String? {
        val explicitHint = step.parts
            .asReversed()
            .mapNotNull { part ->
                when (part) {
                    is SessionStepPart.ToolResult -> part.recoveryHint
                    is SessionStepPart.Error -> part.recoveryHint
                    is SessionStepPart.ApprovalDecision -> {
                        if (part.status == ApprovalRequest.Status.REJECTED) {
                            "The user rejected ${part.type.name.lowercase()} approval for ${part.title}. Explain the need more clearly or choose a safer alternative."
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
            .firstOrNull()
        return explicitHint?.let { "The previous failed step suggests: $it" }
    }

    private fun splitSubtasks(userRequest: String): List<String> {
        val subtasks = userRequest
            .split(Regex("[,.;!?]|\\band\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
            .take(4)
        return if (subtasks.isNotEmpty()) subtasks else listOf(userRequest.trim()).filter { it.isNotBlank() }
    }

    private fun recommendTools(
        userRequest: String,
        availableTools: List<ToolDefinition>,
    ): List<String> {
        if (availableTools.isEmpty()) return emptyList()
        val normalized = userRequest.lowercase()
        val subtaskCount = splitSubtasks(userRequest).size
        val recommendations = linkedSetOf<String>()

        if (subtaskCount >= 2 || normalized.contains("todo") || normalized.contains("task") || normalized.contains("plan")) {
            recommendations += "todo_write"
            recommendations += "todo_read"
        }
        if (
            normalized.contains("skill") ||
            normalized.contains("workflow") ||
            normalized.contains("playbook") ||
            normalized.contains("guide")
        ) {
            recommendations += "skill"
        }
        if (
            normalized.contains("mcp") ||
            normalized.contains("playwright") ||
            normalized.contains("browser") ||
            normalized.contains("sqlite") ||
            normalized.contains("database") ||
            normalized.contains("context7") ||
            normalized.contains("docs")
        ) {
            recommendations += "mcp_list_servers"
            recommendations += "mcp_describe_server"
            recommendations += "mcp_list_tools"
        }
        if (
            normalized.contains("resource") ||
            normalized.contains("reference") ||
            normalized.contains("docs") ||
            normalized.contains("documentation") ||
            normalized.contains("context")
        ) {
            recommendations += "mcp_list_resources"
            recommendations += "mcp_read_resource"
        }
        if (normalized.contains("template")) {
            recommendations += "mcp_list_resource_templates"
        }
        if (
            normalized.contains("prompt") ||
            normalized.contains("slash command") ||
            normalized.contains("slash-command")
        ) {
            recommendations += "mcp_list_prompts"
            recommendations += "mcp_get_prompt"
        }
        if (normalized.contains("project") || normalized.contains("codebase")) {
            recommendations += "read_project_tree"
        }
        if (normalized.contains("search") || normalized.contains("find")) {
            recommendations += "search_text"
        }
        if (normalized.contains("current file") || normalized.contains("selection")) {
            recommendations += "get_current_file"
            recommendations += "get_current_selection"
        }
        if (
            normalized.contains("fix") ||
            normalized.contains("edit") ||
            normalized.contains("patch") ||
            normalized.contains("bug")
        ) {
            recommendations += "read_file"
            recommendations += "apply_patch_preview"
        }
        if (
            normalized.contains("build") ||
            normalized.contains("test") ||
            normalized.contains("run")
        ) {
            recommendations += "run_command"
        }

        val known = availableTools.map { it.id }.toSet()
        return recommendations.filter { it in known }
    }

    private fun looksBroadRequest(userRequest: String): Boolean {
        val normalized = userRequest.lowercase()
        return normalized.contains("project") || normalized.contains("codebase")
    }
}
