package com.idopen.idopen.agent

import java.nio.file.Path

object McpInspectorSupport {
    fun inspectServer(
        projectRoot: Path,
        serverName: String,
        runtime: McpRuntimeSupport = McpRuntimeSupport(),
    ): String {
        val server = McpSupport.available(projectRoot).firstOrNull { it.name.equals(serverName.trim(), ignoreCase = true) }
            ?: return McpSupport.describeToolResult(projectRoot, serverName).content

        val sections = buildList {
            add("Server" to McpSupport.describeToolResult(projectRoot, server.name))
            if (server.transport == "stdio" || server.transport == "http") {
                add("Tools" to runtime.listTools(projectRoot, server.name))
                add("Resources" to runtime.listResources(projectRoot, server.name))
                add("Resource templates" to runtime.listResourceTemplates(projectRoot, server.name))
                add("Prompts" to runtime.listPrompts(projectRoot, server.name))
            } else {
                add(
                    "Runtime" to ToolExecutionResult(
                        content = "Transport ${server.transport} is currently inspect-only. Runtime calls are available for stdio and streamable HTTP servers.",
                        success = false,
                        recoveryHint = "Use mcp_describe_server for legacy transports until runtime support is expanded.",
                    ),
                )
            }
        }
        return buildString {
            appendLine("## MCP Inspection")
            appendLine("Server: ${server.name}")
            appendLine()
            sections.forEachIndexed { index, (title, result) ->
                append("### ")
                appendLine(title)
                appendLine(result.content.trim())
                if (!result.success && !result.recoveryHint.isNullOrBlank()) {
                    appendLine()
                    appendLine("Recovery hint: ${result.recoveryHint}")
                }
                if (index != sections.lastIndex) {
                    appendLine()
                }
            }
        }.trim()
    }
}
