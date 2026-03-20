package com.idopen.idopen.toolwindow

import com.idopen.idopen.settings.DisplayLanguage
import java.nio.file.Path

internal enum class SlashCommandKind {
    ACTION,
    PROMPT,
}

internal enum class SlashCommandId {
    HELP,
    NEW,
    DELETE,
    SETTINGS,
    QUOTA,
    COMMANDS,
    AGENTS,
    MCP,
    SKILLS,
    STOP,
    FILE,
    SELECTION,
    TRUST,
    UNLIMITED,
    PROJECT,
    REVIEW,
    EXPLAIN,
    PLAN,
    TODO,
    CUSTOM,
}

internal enum class SlashToggleIntent {
    ENABLE,
    DISABLE,
    TOGGLE,
}

internal data class SlashCommandDefinition(
    val id: SlashCommandId,
    val name: String,
    val kind: SlashCommandKind,
    val aliases: List<String>,
    private val zhSummary: String,
    private val enSummary: String,
    private val zhDescription: String = zhSummary,
    private val enDescription: String = enSummary,
    val argumentHint: String? = null,
    val promptTemplate: String? = null,
) {
    fun summary(language: DisplayLanguage): String = if (language == DisplayLanguage.ZH_CN) zhSummary else enSummary

    fun description(language: DisplayLanguage): String = if (language == DisplayLanguage.ZH_CN) zhDescription else enDescription

    fun displayLabel(language: DisplayLanguage): String {
        val hint = argumentHint?.takeIf { it.isNotBlank() }?.let { " <$it>" }.orEmpty()
        return "/$name$hint  ${summary(language)}"
    }

    fun matches(token: String): Boolean {
        val normalized = token.lowercase()
        return allNames().any { it == normalized }
    }

    fun matchesPrefix(token: String): Boolean {
        val normalized = token.lowercase()
        return allNames().any { it.startsWith(normalized) }
    }

    fun allNames(): List<String> = buildList {
        add(name.lowercase())
        aliases.mapTo(this) { it.lowercase() }
    }
}

internal data class SlashCommandQuery(
    val token: String,
    val argument: String,
)

internal data class ParsedSlashCommand(
    val definition: SlashCommandDefinition,
    val argument: String,
)

internal object SlashCommandSupport {
    private val builtinDefinitions = listOf(
        SlashCommandDefinition(
            id = SlashCommandId.HELP,
            name = "help",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("?"),
            zhSummary = "显示可用命令",
            enSummary = "Show available commands",
            zhDescription = "显示当前可用的内置 slash commands 及其作用。",
            enDescription = "Show all built-in slash commands and what they do.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.NEW,
            name = "new",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("n"),
            zhSummary = "新建会话",
            enSummary = "Create a new conversation",
            zhDescription = "创建一个新的本地会话并切换过去。",
            enDescription = "Create a new local conversation and switch to it.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.DELETE,
            name = "delete",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("del"),
            zhSummary = "删除当前会话",
            enSummary = "Delete the current conversation",
            zhDescription = "删除当前本地会话，并在执行前要求确认。",
            enDescription = "Delete the current local conversation with confirmation.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.SETTINGS,
            name = "settings",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("config"),
            zhSummary = "打开设置",
            enSummary = "Open settings",
            zhDescription = "打开 IDopen 设置面板。",
            enDescription = "Open the IDopen settings panel.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.QUOTA,
            name = "quota",
            kind = SlashCommandKind.ACTION,
            aliases = emptyList(),
            zhSummary = "查看 ChatGPT 额度",
            enSummary = "Check ChatGPT quota",
            zhDescription = "查询当前 ChatGPT 套餐和额度窗口。",
            enDescription = "Check the current ChatGPT plan and quota windows.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.COMMANDS,
            name = "commands",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("cmds"),
            zhSummary = "\u67e5\u770b\u9879\u76ee\u547d\u4ee4",
            enSummary = "Inspect available commands",
            zhDescription = "\u67e5\u770b\u5f53\u524d\u9879\u76ee\u53ef\u7528\u7684 markdown \u81ea\u5b9a\u4e49\u547d\u4ee4\u53ca\u5176\u6765\u6e90\u3002",
            enDescription = "Inspect the markdown commands available in the current workspace, including global and project sources.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.AGENTS,
            name = "agents",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("agent-list"),
            zhSummary = "\u67e5\u770b\u9879\u76ee agents",
            enSummary = "Inspect available agents",
            zhDescription = "\u67e5\u770b\u5f53\u524d\u9879\u76ee\u53ef\u7528\u7684\u672c\u5730 agent \u5b9a\u4e49\u53ca\u5176\u6765\u6e90\u3002",
            enDescription = "Inspect the agent definitions available in the current workspace, including global and project sources.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.MCP,
            name = "mcp",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("servers"),
            zhSummary = "检查 MCP 服务",
            enSummary = "Inspect MCP servers",
            zhDescription = "查看并检查当前项目配置的 MCP server。",
            enDescription = "Inspect the MCP servers configured for the current project.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.SKILLS,
            name = "skills",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("skill"),
            zhSummary = "查看项目 skills",
            enSummary = "Inspect available skills",
            zhDescription = "查看当前项目可用的本地 skills。",
            enDescription = "Inspect the skills available in the current workspace, including global and project sources.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.STOP,
            name = "stop",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("abort"),
            zhSummary = "停止当前运行",
            enSummary = "Stop the current run",
            zhDescription = "停止当前正在执行的 agent 轮次。",
            enDescription = "Stop the currently running agent turn.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.FILE,
            name = "file",
            kind = SlashCommandKind.ACTION,
            aliases = emptyList(),
            zhSummary = "切换当前文件上下文",
            enSummary = "Toggle current-file context",
            zhDescription = "使用 /file on 或 /file off 显式控制当前文件附件。",
            enDescription = "Use /file on or /file off to explicitly control the current-file attachment.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.SELECTION,
            name = "selection",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("sel"),
            zhSummary = "切换选区上下文",
            enSummary = "Toggle selection context",
            zhDescription = "使用 /selection on 或 /selection off 显式控制当前选区附件。",
            enDescription = "Use /selection on or /selection off to explicitly control the current selection attachment.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.TRUST,
            name = "trust",
            kind = SlashCommandKind.ACTION,
            aliases = emptyList(),
            zhSummary = "切换信任模式",
            enSummary = "Toggle trust mode",
            zhDescription = "使用 /trust on 或 /trust off 显式控制信任模式。",
            enDescription = "Use /trust on or /trust off to explicitly control trust mode.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.UNLIMITED,
            name = "unlimited",
            kind = SlashCommandKind.ACTION,
            aliases = listOf("max"),
            zhSummary = "切换无限制使用",
            enSummary = "Toggle unlimited usage",
            zhDescription = "使用 /unlimited on 或 /unlimited off 控制轮次和工具调用限制。",
            enDescription = "Use /unlimited on or /unlimited off to control turn and tool-call limits.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.PROJECT,
            name = "project",
            kind = SlashCommandKind.PROMPT,
            aliases = listOf("repo"),
            zhSummary = "生成项目分析提示词",
            enSummary = "Generate a project-analysis prompt",
            zhDescription = "生成用于查看和总结项目结构的提示词。",
            enDescription = "Generate a prompt to inspect and summarize the project structure.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.REVIEW,
            name = "review",
            kind = SlashCommandKind.PROMPT,
            aliases = emptyList(),
            zhSummary = "生成代码审查提示词",
            enSummary = "Generate a code-review prompt",
            zhDescription = "生成聚焦 bug、风险和测试缺口的审查提示词。",
            enDescription = "Generate a review prompt focused on bugs, risks, and test gaps.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.EXPLAIN,
            name = "explain",
            kind = SlashCommandKind.PROMPT,
            aliases = emptyList(),
            zhSummary = "生成解释提示词",
            enSummary = "Generate an explanation prompt",
            zhDescription = "生成用于解释当前实现、流程和数据结构的提示词。",
            enDescription = "Generate a prompt to explain the current implementation, flow, and data structures.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.PLAN,
            name = "plan",
            kind = SlashCommandKind.PROMPT,
            aliases = emptyList(),
            zhSummary = "生成规划提示词",
            enSummary = "Generate a planning prompt",
            zhDescription = "生成要求先给出计划再执行的提示词。",
            enDescription = "Generate a prompt that asks for a plan before execution.",
        ),
        SlashCommandDefinition(
            id = SlashCommandId.TODO,
            name = "todo",
            kind = SlashCommandKind.PROMPT,
            aliases = listOf("tasks"),
            zhSummary = "生成 todo 规划提示词",
            enSummary = "Generate a todo-planning prompt",
            zhDescription = "生成要求 agent 先维护 todo 再执行的提示词。",
            enDescription = "Generate a prompt that asks the agent to maintain todos before executing.",
        ),
    )

    fun definitions(): List<SlashCommandDefinition> = builtinDefinitions

    fun definitions(projectRoot: Path?): List<SlashCommandDefinition> = mergeDefinitions(projectRoot)

    fun query(text: String): SlashCommandQuery? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return null
        val remainder = trimmed.drop(1)
        val token = remainder.takeWhile { !it.isWhitespace() }.lowercase()
        val argument = remainder.drop(token.length).trimStart()
        return SlashCommandQuery(token = token, argument = argument)
    }

    fun parse(text: String): ParsedSlashCommand? = parse(text, null)

    fun parse(text: String, projectRoot: Path?): ParsedSlashCommand? {
        val query = query(text) ?: return null
        if (query.token.isBlank()) return null
        val definition = mergeDefinitions(projectRoot).firstOrNull { it.matches(query.token) } ?: return null
        return ParsedSlashCommand(definition, query.argument)
    }

    fun suggestions(text: String): List<SlashCommandDefinition> = suggestions(text, null)

    fun suggestions(text: String, projectRoot: Path?): List<SlashCommandDefinition> {
        val query = query(text) ?: return emptyList()
        val definitions = mergeDefinitions(projectRoot)
        if (query.token.isBlank()) return definitions
        val exactMatch = definitions.firstOrNull { it.name.equals(query.token, ignoreCase = true) }
        if (exactMatch != null && query.argument.isBlank()) {
            return emptyList()
        }
        return definitions.filter { it.matchesPrefix(query.token) }
    }

    fun applySuggestion(text: String, definition: SlashCommandDefinition): String {
        val suffix = query(text)?.argument?.takeIf { it.isNotBlank() }
        val needsSpace = definition.kind == SlashCommandKind.PROMPT ||
            definition.id in setOf(
                SlashCommandId.FILE,
                SlashCommandId.SELECTION,
                SlashCommandId.TRUST,
                SlashCommandId.UNLIMITED,
            )
        return when {
            suffix != null -> "/${definition.name} $suffix"
            needsSpace -> "/${definition.name} "
            else -> "/${definition.name}"
        }
    }

    fun resolveToggle(argument: String): SlashToggleIntent? {
        val token = argument.trim().substringBefore(' ').lowercase()
        if (token.isBlank()) return SlashToggleIntent.TOGGLE
        return when (token) {
            "on", "enable", "enabled", "true", "1", "yes", "y", "开", "开启", "打开", "启用" -> SlashToggleIntent.ENABLE
            "off", "disable", "disabled", "false", "0", "no", "n", "关", "关闭", "禁用" -> SlashToggleIntent.DISABLE
            "toggle", "switch", "切换" -> SlashToggleIntent.TOGGLE
            else -> null
        }
    }

    fun buildPrompt(command: ParsedSlashCommand, language: DisplayLanguage): String {
        val argument = command.argument.trim()
        return when (command.definition.id) {
            SlashCommandId.PROJECT -> if (language == DisplayLanguage.ZH_CN) {
                if (argument.isBlank()) {
                    "请查看当前项目，并总结技术栈、目录结构、核心流程、关键模块，以及当前完成度。"
                } else {
                    "请重点围绕以下主题查看当前项目：$argument。总结技术栈、目录结构、核心流程、关键模块，以及当前完成度。"
                }
            } else {
                if (argument.isBlank()) {
                    "Inspect the current project and summarize the stack, directory layout, core flow, key modules, and current maturity."
                } else {
                    "Inspect the current project with special attention to: $argument. Summarize the stack, directory layout, core flow, key modules, and current maturity."
                }
            }

            SlashCommandId.REVIEW -> if (language == DisplayLanguage.ZH_CN) {
                if (argument.isBlank()) {
                    "请进行一次代码审查，优先关注 bug、风险、行为回归，以及缺失的测试。"
                } else {
                    "请针对以下内容进行代码审查：$argument。优先关注 bug、风险、行为回归，以及缺失的测试。"
                }
            } else {
                if (argument.isBlank()) {
                    "Perform a code review and prioritize bugs, risks, behavioral regressions, and missing tests."
                } else {
                    "Perform a code review for: $argument. Prioritize bugs, risks, behavioral regressions, and missing tests."
                }
            }

            SlashCommandId.EXPLAIN -> if (language == DisplayLanguage.ZH_CN) {
                if (argument.isBlank()) {
                    "请解释当前实现，包括职责划分、主要流程、关键数据结构，以及调用关系。"
                } else {
                    "请解释这个目标：$argument。包括职责划分、主要流程、关键数据结构，以及调用关系。"
                }
            } else {
                if (argument.isBlank()) {
                    "Explain the current implementation, including responsibilities, main flow, key data structures, and call relationships."
                } else {
                    "Explain this target: $argument. Include responsibilities, main flow, key data structures, and call relationships."
                }
            }

            SlashCommandId.PLAN -> if (language == DisplayLanguage.ZH_CN) {
                if (argument.isBlank()) {
                    "请先给出一个清晰、可执行的计划，然后再开始处理。"
                } else {
                    "请先给出一个清晰、可执行的计划，然后再处理这个任务：$argument"
                }
            } else {
                if (argument.isBlank()) {
                    "Based on the current context, provide a clear actionable plan first, then start working."
                } else {
                    "Provide a clear actionable plan first, then start working on this task: $argument"
                }
            }

            SlashCommandId.TODO -> if (language == DisplayLanguage.ZH_CN) {
                if (argument.isBlank()) {
                    "请先创建或更新当前会话的 todo 列表，把工作拆成带状态的步骤，然后再继续执行。"
                } else {
                    "请先创建或更新当前会话的 todo 列表，把工作拆成带状态的步骤，然后再处理这个任务：$argument"
                }
            } else {
                if (argument.isBlank()) {
                    "First create or update the current session todo list, break the work into steps with statuses, and then continue execution."
                } else {
                    "First create or update the current session todo list, break the work into steps with statuses, and then handle this task: $argument"
                }
            }

            SlashCommandId.CUSTOM -> expandCustomTemplate(command.definition.promptTemplate.orEmpty(), argument)
            else -> ""
        }
    }

    fun helpText(language: DisplayLanguage): String = helpText(language, null)

    fun helpText(language: DisplayLanguage, projectRoot: Path?): String {
        val definitions = mergeDefinitions(projectRoot)
        val actionHeader = if (language == DisplayLanguage.ZH_CN) "本地动作命令" else "Local action commands"
        val promptHeader = if (language == DisplayLanguage.ZH_CN) "内置提示词命令" else "Built-in prompt commands"
        val customHeader = if (language == DisplayLanguage.ZH_CN) "项目自定义命令" else "Project custom commands"

        val actionLines = definitions
            .filter { it.kind == SlashCommandKind.ACTION }
            .joinToString("\n") { "- /${it.name}  ${it.summary(language)}" }
        val promptLines = definitions
            .filter { it.kind == SlashCommandKind.PROMPT && it.id != SlashCommandId.CUSTOM }
            .joinToString("\n") { "- /${it.name}  ${it.summary(language)}" }
        val customLines = definitions
            .filter { it.id == SlashCommandId.CUSTOM }
            .joinToString("\n") { "- /${it.name}  ${it.summary(language)}" }

        val footer = if (language == DisplayLanguage.ZH_CN) {
            "\n开关命令支持 on/off，也支持中文的 开/关/开启/关闭。"
        } else {
            "\nToggle commands accept on/off and also understand Chinese variants like 开/关."
        }

        return buildString {
            appendLine(actionHeader)
            appendLine(actionLines)
            appendLine()
            appendLine(promptHeader)
            appendLine(promptLines)
            if (customLines.isNotBlank()) {
                appendLine()
                appendLine(customHeader)
                appendLine(customLines)
            }
            append(footer)
        }.trim()
    }

    private fun mergeDefinitions(projectRoot: Path?): List<SlashCommandDefinition> {
        val result = mutableListOf<SlashCommandDefinition>()
        val seen = linkedSetOf<String>()
        val combined = buildList {
            if (projectRoot != null) addAll(projectDefinitions(projectRoot))
            addAll(builtinDefinitions)
        }
        combined.forEach { definition ->
            val names = definition.allNames()
            if (names.any { it in seen }) return@forEach
            result += definition
            seen += names
        }
        return result
    }

    private fun projectDefinitions(projectRoot: Path): List<SlashCommandDefinition> {
        return ProjectSlashCommandSupport.available(projectRoot).map { command ->
            val summary = command.description.ifBlank {
                "Project command from ${command.path.fileName}"
            }
            val metadata = buildList {
                command.argumentHint?.let { add("args: $it") }
                command.agent?.let { add("agent: $it") }
                command.model?.let { add("model: $it") }
                add("path: ${projectRoot.relativize(command.path).toString().replace('\\', '/')}")
            }.joinToString(" | ")
            SlashCommandDefinition(
                id = SlashCommandId.CUSTOM,
                name = command.name,
                kind = SlashCommandKind.PROMPT,
                aliases = emptyList(),
                zhSummary = summary,
                enSummary = summary,
                zhDescription = "$summary\n$metadata",
                enDescription = "$summary\n$metadata",
                argumentHint = command.argumentHint,
                promptTemplate = command.template,
            )
        }
    }

    private fun expandCustomTemplate(template: String, argument: String): String {
        val trimmedTemplate = template.trim()
        if (trimmedTemplate.isBlank()) return ""
        val tokens = splitArguments(argument)
        var expanded = trimmedTemplate.replace("\$ARGUMENTS", argument)
        for (index in tokens.indices.reversed()) {
            expanded = expanded.replace("\$${index + 1}", tokens[index])
        }
        return expanded
    }

    private fun splitArguments(argument: String): List<String> {
        if (argument.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escape = false
        argument.forEach { ch ->
            when {
                escape -> {
                    current.append(ch)
                    escape = false
                }

                ch == '\\' && quote != null -> escape = true
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote == null && ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.setLength(0)
                    }
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
