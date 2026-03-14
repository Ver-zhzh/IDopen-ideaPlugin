package com.idopen.idopen.agent

enum class CommandPolicy {
    AUTO_APPROVE,
    REQUIRE_APPROVAL,
    BLOCKED,
}

data class CommandDecision(
    val policy: CommandPolicy,
    val reason: String? = null,
)

object CommandSafetySupport {
    private val safeCommands = setOf(
        "dir",
        "ls",
        "pwd",
        "tree",
        "get-childitem",
        "get-location",
        "get-item",
        "get-command",
        "cat",
        "type",
        "more",
        "get-content",
        "where",
        "which",
        "rg",
        "find",
        "findstr",
        "select-string",
        "head",
        "tail",
        "wc",
        "sort",
        "uniq",
    )

    private val approvalOnlyCommands = setOf(
        "rm",
        "del",
        "erase",
        "rmdir",
        "rd",
        "mv",
        "move",
        "ren",
        "rename",
        "cp",
        "copy",
        "copy-item",
        "move-item",
        "remove-item",
        "set-content",
        "add-content",
        "out-file",
        "touch",
        "mkdir",
        "md",
        "new-item",
        "npm",
        "pnpm",
        "yarn",
        "pip",
        "uv",
        "cargo",
        "go",
        "gradle",
        "gradlew",
        "mvn",
        "python",
        "node",
        "java",
    )

    fun evaluate(command: String): CommandDecision {
        val normalized = command.trim()
        if (normalized.isBlank()) {
            return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "空命令需要人工确认。")
        }

        detectBlockedPattern(normalized)?.let { reason ->
            return CommandDecision(CommandPolicy.BLOCKED, reason)
        }

        if (containsChainOperator(normalized)) {
            return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "包含串联执行符，需要人工确认。")
        }

        if (containsRedirection(normalized)) {
            return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "包含重定向或子命令，需要人工确认。")
        }

        val pipeline = splitPipeline(normalized)
        if (pipeline.isEmpty()) {
            return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "无法识别命令结构。")
        }

        val decisions = pipeline.map(::evaluateSegment)
        return when {
            decisions.any { it.policy == CommandPolicy.BLOCKED } -> decisions.first { it.policy == CommandPolicy.BLOCKED }
            decisions.all { it.policy == CommandPolicy.AUTO_APPROVE } -> {
                CommandDecision(CommandPolicy.AUTO_APPROVE, "只读命令自动放行。")
            }
            else -> CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "命令可能修改环境，需要人工确认。")
        }
    }

    private fun evaluateSegment(segment: String): CommandDecision {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) {
            return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "空命令片段需要人工确认。")
        }
        val executable = tokens.first().lowercase()

        if (executable in safeCommands) {
            return CommandDecision(CommandPolicy.AUTO_APPROVE)
        }

        if (executable == "git") {
            return evaluateGit(tokens.drop(1))
        }

        if (executable in approvalOnlyCommands) {
            return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "该命令可能修改项目或环境。")
        }

        return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "未知命令默认需要人工确认。")
    }

    private fun evaluateGit(args: List<String>): CommandDecision {
        if (args.isEmpty()) {
            return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "git 顶层命令需要人工确认。")
        }
        val subcommand = args.first().lowercase()
        if (subcommand in setOf("status", "diff", "show", "log", "branch", "rev-parse", "ls-files", "blame", "grep")) {
            return CommandDecision(CommandPolicy.AUTO_APPROVE)
        }
        if (subcommand == "reset" && args.any { it.equals("--hard", ignoreCase = true) }) {
            return CommandDecision(CommandPolicy.BLOCKED, "已阻止 git reset --hard。")
        }
        if (subcommand == "checkout" && args.any { it == "--" }) {
            return CommandDecision(CommandPolicy.BLOCKED, "已阻止使用 git checkout -- 覆盖工作区。")
        }
        if (subcommand == "clean" && args.any { it.contains("f", ignoreCase = true) }) {
            return CommandDecision(CommandPolicy.BLOCKED, "已阻止 git clean 强制删除文件。")
        }
        return CommandDecision(CommandPolicy.REQUIRE_APPROVAL, "git 写操作需要人工确认。")
    }

    private fun detectBlockedPattern(command: String): String? {
        val lowered = command.lowercase()
        if (Regex("""\b(curl|wget|invoke-webrequest)\b.+\|\s*(sh|bash|pwsh|powershell)\b""").containsMatchIn(lowered)) {
            return "已阻止直接下载并执行远程脚本。"
        }
        if (Regex("""\b(format|diskpart|shutdown|reboot|halt|mkfs|dd)\b""").containsMatchIn(lowered)) {
            return "已阻止高风险系统级命令。"
        }
        return null
    }

    private fun containsChainOperator(command: String): Boolean {
        return "&&" in command || "||" in command || ";" in command
    }

    private fun containsRedirection(command: String): Boolean {
        return ">" in command || "<" in command || "`" in command || "\$(" in command || "<(" in command
    }

    private fun splitPipeline(command: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var index = 0
        while (index < command.length) {
            val char = command[index]
            when {
                char == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(char)
                }
                char == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(char)
                }
                char == '|' && !inSingle && !inDouble -> {
                    if (index + 1 < command.length && command[index + 1] == '|') {
                        current.append("||")
                        index += 1
                    } else {
                        segments += current.toString().trim()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
            index += 1
        }
        if (current.isNotBlank()) {
            segments += current.toString().trim()
        }
        return segments.filter { it.isNotBlank() }
    }

    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (char in command) {
            when {
                char == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(char)
                }
                char == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(char)
                }
                char.isWhitespace() && !inSingle && !inDouble -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }
}
