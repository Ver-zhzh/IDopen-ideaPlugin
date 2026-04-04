package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class McpScope {
    USER,
    PROJECT,
    LOCAL,
}

data class LoadedMcpServer(
    val name: String,
    val scope: McpScope,
    val sourcePath: Path,
    val transport: String,
    val command: String?,
    val args: List<String>,
    val url: String?,
    val env: Map<String, String>,
    val headers: Map<String, String>,
    val oauthScopes: List<String>,
)

object McpSupport {
    private val mapper = ObjectMapper()

    fun available(projectRoot: Path, userHome: Path = defaultUserHome()): List<LoadedMcpServer> {
        val merged = linkedMapOf<String, LoadedMcpServer>()
        configPaths(projectRoot, userHome).forEach { candidate ->
            val root = readConfig(candidate.path) ?: return@forEach
            val servers = root.path("mcpServers")
            if (!servers.isObject) return@forEach
            val fields = servers.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val name = entry.key.trim()
                if (name.isBlank()) continue
                val node = entry.value
                if (!node.isObject) continue
                val key = name.lowercase()
                if (node.path("disabled").asBoolean(false)) {
                    merged.remove(key)
                    continue
                }
                parseServer(name, candidate.scope, candidate.path, node)?.let { merged[key] = it }
            }
        }
        return merged.values.sortedBy { it.name.lowercase() }
    }

    fun toolDescription(projectRoot: Path): String {
        val list = available(projectRoot)
        return if (list.isEmpty()) {
            "Inspect configured MCP servers for the current project. No MCP servers are currently configured. Open Settings | IDopen MCP to edit the user or project MCP config."
        } else {
            buildString {
                appendLine("Inspect configured MCP servers for the current project before relying on external MCP capabilities.")
                appendLine()
                appendLine("Available configured MCP servers:")
                list.forEach { server ->
                    append("- ")
                    append(server.name)
                    append(" [")
                    append(server.scope.name.lowercase())
                    append("] ")
                    append(server.transport)
                    append(": ")
                    appendLine(server.commandLineOrUrl())
                }
            }.trim()
        }
    }

    fun systemPromptSection(projectRoot: Path): String? {
        val list = available(projectRoot)
        if (list.isEmpty()) return null
        return buildString {
            appendLine("Configured MCP servers are visible through mcp_list_servers and mcp_describe_server.")
            appendLine("After choosing a supported MCP server, use mcp_list_tools before mcp_call_tool, mcp_list_resources before mcp_read_resource, and mcp_list_prompts before mcp_get_prompt so names and schemas stay grounded in the actual server response.")
            appendLine("<available_mcp_servers>")
            list.forEach { server ->
                appendLine("  <server>")
                appendLine("    <name>${xmlEscape(server.name)}</name>")
                appendLine("    <scope>${server.scope.name.lowercase()}</scope>")
                appendLine("    <transport>${xmlEscape(server.transport)}</transport>")
                appendLine("    <summary>${xmlEscape(server.commandLineOrUrl())}</summary>")
                appendLine("  </server>")
            }
            append("</available_mcp_servers>")
        }
    }

    fun listToolResult(projectRoot: Path): ToolExecutionResult {
        val list = available(projectRoot)
        if (list.isEmpty()) {
            return ToolExecutionResult("No MCP servers are configured for the current project. Open Settings | IDopen MCP to edit the user or project MCP config.")
        }
        val content = buildString {
            appendLine("## MCP Servers")
            list.forEach { server ->
                append("- **")
                append(server.name)
                append("** [")
                append(server.scope.name.lowercase())
                append("] ")
                append(server.transport)
                append(" | ")
                appendLine(server.commandLineOrUrl())
            }
        }.trim()
        return ToolExecutionResult(content)
    }

    fun describeToolResult(projectRoot: Path, requestedName: String): ToolExecutionResult {
        val name = requestedName.trim()
        if (name.isBlank()) {
            return ToolExecutionResult(
                content = "Missing MCP server name parameter.",
                success = false,
                recoveryHint = "Call mcp_describe_server with one of the exact names returned by mcp_list_servers.",
            )
        }
        val server = available(projectRoot).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return ToolExecutionResult(
                content = "MCP server \"$name\" not found. Available servers: ${available(projectRoot).joinToString(", ") { it.name }.ifBlank { "none" }}",
                success = false,
                recoveryHint = "Use mcp_list_servers first, then retry with one of the exact server names.",
            )
        return ToolExecutionResult(
            buildString {
                appendLine("<mcp_server name=\"${xmlEscape(server.name)}\">")
                appendLine("Scope: ${server.scope.name.lowercase()}")
                appendLine("Source: ${server.sourcePath.toString().replace('\\', '/')}")
                appendLine("Transport: ${server.transport}")
                server.command?.let { appendLine("Command: $it") }
                if (server.args.isNotEmpty()) {
                    appendLine("Args: ${server.args.joinToString(" ")}")
                }
                server.url?.let { appendLine("URL: $it") }
                if (server.env.isNotEmpty()) {
                    appendLine("Env keys: ${server.env.keys.sorted().joinToString(", ")}")
                }
                if (server.headers.isNotEmpty()) {
                    appendLine("Header keys: ${server.headers.keys.sorted().joinToString(", ")}")
                }
                if (server.oauthScopes.isNotEmpty()) {
                    appendLine("OAuth scopes: ${server.oauthScopes.joinToString(", ")}")
                }
                append("</mcp_server>")
            }.trim(),
        )
    }

    internal fun configPaths(projectRoot: Path, userHome: Path): List<McpConfigPath> {
        val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedUserHome = userHome.toAbsolutePath().normalize()
        return listOf(
            McpConfigPath(normalizedUserHome.resolve(".claude").resolve(".mcp.json"), McpScope.USER),
            McpConfigPath(normalizedUserHome.resolve(".claude.json"), McpScope.USER),
            McpConfigPath(normalizedUserHome.resolve(".idopen.json"), McpScope.USER),
            McpConfigPath(normalizedUserHome.resolve(".idopen").resolve("mcp.json"), McpScope.USER),
            McpConfigPath(normalizedProjectRoot.resolve(".claude").resolve(".mcp.json"), McpScope.LOCAL),
            McpConfigPath(normalizedProjectRoot.resolve(".mcp.json"), McpScope.PROJECT),
            McpConfigPath(normalizedProjectRoot.resolve(".idopen").resolve("mcp.json"), McpScope.PROJECT),
        )
    }

    private fun parseServer(
        name: String,
        scope: McpScope,
        sourcePath: Path,
        node: JsonNode,
    ): LoadedMcpServer? {
        val command = node.path("command").asText("").trim().ifBlank { null }
        val url = node.path("url").asText("").trim().ifBlank { null }
        if (command == null && url == null) return null
        val explicitType = node.path("type").asText("").trim().lowercase()
        val transport = when {
            explicitType in setOf("http", "sse", "stdio") -> explicitType
            url != null -> "http"
            else -> "stdio"
        }
        return LoadedMcpServer(
            name = name,
            scope = scope,
            sourcePath = sourcePath.toAbsolutePath().normalize(),
            transport = transport,
            command = command,
            args = node.path("args").takeIf { it.isArray }?.mapNotNull { child ->
                child.asText("").trim().ifBlank { null }
            }.orEmpty(),
            url = url,
            env = readStringMap(node.path("env")),
            headers = readStringMap(node.path("headers")),
            oauthScopes = node.path("oauth").path("scopes").takeIf { it.isArray }?.mapNotNull { child ->
                child.asText("").trim().ifBlank { null }
            }.orEmpty(),
        )
    }

    private fun readConfig(path: Path): JsonNode? {
        if (!Files.isRegularFile(path)) return null
        return runCatching { mapper.readTree(Files.readString(path)) }.getOrNull()
    }

    private fun readStringMap(node: JsonNode): Map<String, String> {
        if (!node.isObject) return emptyMap()
        val result = linkedMapOf<String, String>()
        val fields = node.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            val value = entry.value.asText("").trim()
            if (entry.key.isNotBlank() && value.isNotBlank()) {
                result[entry.key] = value
            }
        }
        return result
    }

    private fun LoadedMcpServer.commandLineOrUrl(): String {
        return url ?: buildString {
            append(command.orEmpty())
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" "))
            }
        }.trim().ifBlank { "<unknown>" }
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun defaultUserHome(): Path {
        return Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize()
    }

    internal data class McpConfigPath(
        val path: Path,
        val scope: McpScope,
    )
}
