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
        val planningNotes = buildList {
            if (!runtimeProfile.includeTools) {
                add("Tool calling is unavailable for the current model, so prefer direct analysis over tool-dependent plans.")
            }
            if (looksBroadRequest(userRequest) && recommendedTools.isEmpty()) {
                add("Start with a narrow project inspection before proposing code changes.")
            }
            if (userRequest.contains("fix", ignoreCase = true) || userRequest.contains("修", ignoreCase = true)) {
                add("Read the exact file or selection before editing, and prefer focused edits over full-file rewrites.")
            }
            if (userRequest.contains("search", ignoreCase = true) || userRequest.contains("查找") || userRequest.contains("调用")) {
                add("Search the project first, then read only the files that look relevant.")
            }
        }
        return AgentTurnPlan(
            subtasks = subtasks,
            recommendedTools = recommendedTools,
            recoveryHint = lastFailedStep?.summary?.let {
                "The previous failed step was: $it. Avoid repeating the same action unchanged."
            },
            planningNotes = planningNotes,
        )
    }

    private fun splitSubtasks(userRequest: String): List<String> {
        val subtasks = userRequest
            .split(Regex("[，,。.!！？;；]|\\band\\b", RegexOption.IGNORE_CASE))
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
        val recommendations = linkedSetOf<String>()
        if (normalized.contains("project") || normalized.contains("项目")) {
            recommendations += "read_project_tree"
        }
        if (
            normalized.contains("search") ||
            normalized.contains("find") ||
            normalized.contains("查找") ||
            normalized.contains("调用") ||
            normalized.contains("引用")
        ) {
            recommendations += "search_text"
        }
        if (
            normalized.contains("current file") ||
            normalized.contains("selection") ||
            normalized.contains("当前文件") ||
            normalized.contains("当前选区") ||
            normalized.contains("解释")
        ) {
            recommendations += "get_current_file"
            recommendations += "get_current_selection"
        }
        if (
            normalized.contains("fix") ||
            normalized.contains("edit") ||
            normalized.contains("patch") ||
            normalized.contains("修改") ||
            normalized.contains("重构") ||
            normalized.contains("bug")
        ) {
            recommendations += "read_file"
            recommendations += "apply_patch_preview"
        }
        if (
            normalized.contains("build") ||
            normalized.contains("test") ||
            normalized.contains("run") ||
            normalized.contains("命令") ||
            normalized.contains("测试") ||
            normalized.contains("运行")
        ) {
            recommendations += "run_command"
        }
        val known = availableTools.map { it.id }.toSet()
        return recommendations.filter { it in known }
    }

    private fun looksBroadRequest(userRequest: String): Boolean {
        val normalized = userRequest.lowercase()
        return normalized.contains("项目") ||
            normalized.contains("project") ||
            normalized.contains("codebase") ||
            normalized.contains("整体") ||
            normalized.contains("整个")
    }
}
