package com.idopen.idopen.agent

import com.idopen.idopen.settings.DisplayLanguage

enum class SessionMode {
    GENERAL,
    PLAN,
    BUILD,
    REVIEW,
    ;

    companion object {
        fun fromStored(value: String?): SessionMode {
            return entries.firstOrNull { it.name == value } ?: GENERAL
        }
    }
}

object SessionModeSupport {
    fun all(): List<SessionMode> = SessionMode.entries

    fun label(mode: SessionMode, language: DisplayLanguage): String {
        return when (mode) {
            SessionMode.GENERAL -> if (language == DisplayLanguage.ZH_CN) "通用模式" else "General mode"
            SessionMode.PLAN -> if (language == DisplayLanguage.ZH_CN) "规划模式" else "Planning mode"
            SessionMode.BUILD -> if (language == DisplayLanguage.ZH_CN) "构建模式" else "Build mode"
            SessionMode.REVIEW -> if (language == DisplayLanguage.ZH_CN) "审查模式" else "Review mode"
        }
    }

    fun shortLabel(mode: SessionMode, language: DisplayLanguage): String {
        return when (mode) {
            SessionMode.GENERAL -> if (language == DisplayLanguage.ZH_CN) "通用" else "General"
            SessionMode.PLAN -> if (language == DisplayLanguage.ZH_CN) "规划" else "Plan"
            SessionMode.BUILD -> if (language == DisplayLanguage.ZH_CN) "构建" else "Build"
            SessionMode.REVIEW -> if (language == DisplayLanguage.ZH_CN) "审查" else "Review"
        }
    }

    fun description(mode: SessionMode, language: DisplayLanguage): String {
        return when (mode) {
            SessionMode.GENERAL -> if (language == DisplayLanguage.ZH_CN) {
                "平衡分析、修改和验证，按用户请求推进。"
            } else {
                "Balance analysis, implementation, and verification based on the user's request."
            }

            SessionMode.PLAN -> if (language == DisplayLanguage.ZH_CN) {
                "先给出清晰计划和 todo，再进入修改或执行。"
            } else {
                "Lead with a concrete plan and todo list before editing or executing."
            }

            SessionMode.BUILD -> if (language == DisplayLanguage.ZH_CN) {
                "偏向读代码、改代码、跑验证的执行闭环。"
            } else {
                "Bias toward inspect-edit-verify loops and execution."
            }

            SessionMode.REVIEW -> if (language == DisplayLanguage.ZH_CN) {
                "偏向问题审查，优先找 bug、风险和测试缺口。"
            } else {
                "Bias toward review findings, bugs, risks, and testing gaps."
            }
        }
    }

    fun helpText(currentMode: SessionMode, language: DisplayLanguage): String {
        val header = if (language == DisplayLanguage.ZH_CN) {
            "当前会话模式：${label(currentMode, language)}"
        } else {
            "Current session mode: ${label(currentMode, language)}"
        }
        val body = all().joinToString("\n") { mode ->
            "- ${shortLabel(mode, language)}: ${description(mode, language)}"
        }
        val footer = if (language == DisplayLanguage.ZH_CN) {
            "\n可用命令：/mode general | plan | build | review"
        } else {
            "\nAvailable command: /mode general | plan | build | review"
        }
        return "$header\n\n$body$footer"
    }

    fun parse(raw: String): SessionMode? {
        val normalized = raw.trim().substringBefore(' ').lowercase()
        if (normalized.isBlank()) return null
        return when (normalized) {
            "general", "default", "chat", "common", "通用", "默认", "普通" -> SessionMode.GENERAL
            "plan", "planning", "planner", "规划", "计划" -> SessionMode.PLAN
            "build", "coding", "coder", "execute", "exec", "构建", "开发", "编码", "执行" -> SessionMode.BUILD
            "review", "audit", "analysis", "审查", "评审", "审核" -> SessionMode.REVIEW
            else -> null
        }
    }

    fun planningNotes(mode: SessionMode): List<String> {
        return when (mode) {
            SessionMode.GENERAL -> emptyList()
            SessionMode.PLAN -> listOf(
                "Current session mode is planning, so lead with a concrete plan before making changes.",
                "Keep the todo list current and avoid jumping into edits before the plan is clear.",
            )

            SessionMode.BUILD -> listOf(
                "Current session mode is build, so prefer a tight inspect-edit-verify loop.",
                "When changes are made, run focused verification when practical instead of stopping at code edits.",
            )

            SessionMode.REVIEW -> listOf(
                "Current session mode is review, so prioritize findings, risks, and test gaps over implementation.",
                "Avoid making edits unless the user explicitly shifts back to an execution-oriented request.",
            )
        }
    }

    fun recommendedTools(mode: SessionMode): List<String> {
        return when (mode) {
            SessionMode.GENERAL -> emptyList()
            SessionMode.PLAN -> listOf("todo_write", "todo_read", "read_project_tree", "search_text", "read_file")
            SessionMode.BUILD -> listOf("todo_write", "todo_read", "read_project_tree", "search_text", "read_file", "apply_patch_preview", "run_command")
            SessionMode.REVIEW -> listOf("read_project_tree", "search_text", "read_file", "get_current_file", "get_current_selection")
        }
    }

    fun systemPromptInstruction(mode: SessionMode): String? {
        return when (mode) {
            SessionMode.GENERAL -> null
            SessionMode.PLAN -> "Current session mode: planning. Start with a concrete plan, keep todos updated, and avoid edits or commands until the plan is clear."
            SessionMode.BUILD -> "Current session mode: build. Prefer inspect-edit-verify loops, keep todos current, and run focused checks after changes when practical."
            SessionMode.REVIEW -> "Current session mode: review. Prioritize bugs, risks, regressions, and missing tests. Avoid editing unless the user explicitly asks for implementation."
        }
    }

    fun changedMessage(mode: SessionMode, language: DisplayLanguage): String {
        return if (language == DisplayLanguage.ZH_CN) {
            "已切换到${label(mode, language)}。${description(mode, language)}"
        } else {
            "Switched to ${label(mode, language)}. ${description(mode, language)}"
        }
    }
}
