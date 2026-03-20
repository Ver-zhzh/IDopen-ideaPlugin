package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class McpRemoteTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonNode?,
)

data class McpCallResult(
    val content: String,
    val isError: Boolean,
)

data class McpRemoteResource(
    val uri: String,
    val name: String,
    val description: String?,
    val mimeType: String?,
    val size: Long?,
)

data class McpResourceContent(
    val uri: String,
    val mimeType: String?,
    val text: String?,
    val blob: String?,
)

data class McpRemoteResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val description: String?,
    val mimeType: String?,
)

data class McpPromptArgument(
    val name: String,
    val description: String?,
    val required: Boolean,
)

data class McpRemotePrompt(
    val name: String,
    val description: String?,
    val arguments: List<McpPromptArgument>,
)

data class McpPromptMessage(
    val role: String,
    val content: JsonNode,
)

data class McpPromptResult(
    val description: String?,
    val messages: List<McpPromptMessage>,
)

interface McpSession : Closeable {
    fun listTools(): List<McpRemoteTool>

    fun callTool(name: String, arguments: JsonNode): McpCallResult

    fun listResources(): List<McpRemoteResource>

    fun readResource(uri: String): List<McpResourceContent>

    fun listResourceTemplates(): List<McpRemoteResourceTemplate>

    fun listPrompts(): List<McpRemotePrompt>

    fun getPrompt(name: String, arguments: JsonNode): McpPromptResult
}

class McpRuntimeSupport(
    private val mapper: ObjectMapper = ObjectMapper(),
    private val sessionFactory: (LoadedMcpServer, Path) -> McpSession = { server, projectRoot ->
        when (server.transport) {
            "http" -> HttpMcpSession(server, ObjectMapper())
            else -> StdioMcpSession(server, projectRoot, ObjectMapper())
        }
    },
) : Closeable {
    companion object {
        private const val SESSION_TTL_MS = 5 * 60 * 1000L
        private const val CATALOG_TTL_MS = 2 * 60 * 1000L
    }

    private val sessionCache = linkedMapOf<String, CachedSession>()
    private val toolCache = linkedMapOf<String, CachedToolCatalog>()
    private val resourceCache = linkedMapOf<String, CachedResourceCatalog>()
    private val resourceTemplateCache = linkedMapOf<String, CachedResourceTemplateCatalog>()
    private val promptCache = linkedMapOf<String, CachedPromptCatalog>()

    fun listTools(projectRoot: Path, serverName: String): ToolExecutionResult {
        val resolved = resolveServer(projectRoot, serverName) ?: return missingServerResult(projectRoot, serverName)
        if (!supportsRuntimeTransport(resolved.transport)) {
            return unsupportedTransport(
                resolved,
                "tools",
                "Choose a stdio or streamable HTTP MCP server first, or keep using mcp_describe_server for transports that are still inspect-only.",
            )
        }
        return runCatching {
            val tools = cachedOrFetchTools(resolved, projectRoot, refresh = false)
            if (tools.isEmpty()) {
                ToolExecutionResult("MCP server \"${resolved.name}\" returned no tools.")
            } else {
                ToolExecutionResult(
                    buildString {
                        appendLine("## MCP Tools (${resolved.name})")
                        tools.forEach { tool ->
                            append("- **")
                            append(tool.name)
                            append("**")
                            tool.description?.takeIf { it.isNotBlank() }?.let {
                                append(": ")
                                append(it.trim())
                            }
                            tool.inputSchema?.takeIf { !it.isMissingNode && !it.isNull }?.let { schema ->
                                append(" | inputSchema=")
                                append(schema.toString())
                            }
                            appendLine()
                        }
                    }.trim(),
                )
            }
        }.getOrElse { exception ->
            ToolExecutionResult(
                content = "Failed to list MCP tools for \"${resolved.name}\": ${exception.message ?: "unknown error"}",
                success = false,
                recoveryHint = "Use mcp_describe_server first to confirm the server transport and command. If it is stdio, verify the command starts successfully in this project.",
            )
        }
    }

    fun callTool(projectRoot: Path, serverName: String, toolName: String, arguments: JsonNode): ToolExecutionResult {
        val resolved = resolveServer(projectRoot, serverName) ?: return missingServerResult(projectRoot, serverName)
        if (!supportsRuntimeTransport(resolved.transport)) {
            return unsupportedTransport(
                resolved,
                "tool calls",
                "Choose a stdio or streamable HTTP MCP server first, then use mcp_list_tools before calling one of its tools.",
            )
        }
        if (toolName.isBlank()) {
            return ToolExecutionResult(
                content = "Missing MCP tool name parameter.",
                success = false,
                recoveryHint = "Call mcp_list_tools first and retry with one exact MCP tool name.",
            )
        }
        return runCatching {
            val tools = cachedOrFetchTools(resolved, projectRoot, refresh = false)
            val matchedTool = tools.firstOrNull { it.name.equals(toolName.trim(), ignoreCase = true) }
                ?: return ToolExecutionResult(
                    content = "MCP tool \"$toolName\" is not exposed by server \"${resolved.name}\". Available tools: ${tools.joinToString(", ") { it.name }.ifBlank { "none" }}",
                    success = false,
                    recoveryHint = "Re-run mcp_list_tools and retry with one exact MCP tool name from that list.",
                )
            val result = withSession(resolved, projectRoot) { session ->
                session.callTool(matchedTool.name, arguments)
            }
            ToolExecutionResult(
                content = result.content,
                success = !result.isError,
                recoveryHint = if (result.isError) {
                    "The MCP server reported a tool error. Re-check the tool name and arguments, and prefer calling mcp_list_tools before retrying."
                } else {
                    null
                },
            )
        }.getOrElse { exception ->
            ToolExecutionResult(
                content = "Failed to call MCP tool \"$toolName\" on \"${resolved.name}\": ${exception.message ?: "unknown error"}",
                success = false,
                recoveryHint = "Use mcp_list_tools to confirm the tool exists and matches the expected input schema before retrying.",
            )
        }
    }

    fun listResources(projectRoot: Path, serverName: String): ToolExecutionResult {
        val resolved = resolveServer(projectRoot, serverName) ?: return missingServerResult(projectRoot, serverName)
        if (!supportsRuntimeTransport(resolved.transport)) {
            return unsupportedTransport(
                resolved,
                "resources",
                "Choose a stdio or streamable HTTP MCP server first, then use mcp_list_resources before trying to read one resource.",
            )
        }
        return runCatching {
            val resources = cachedOrFetchResources(resolved, projectRoot, refresh = false)
            if (resources.isEmpty()) {
                ToolExecutionResult("MCP server \"${resolved.name}\" returned no resources.")
            } else {
                ToolExecutionResult(
                    buildString {
                        appendLine("## MCP Resources (${resolved.name})")
                        resources.forEach { resource ->
                            append("- **")
                            append(resource.name)
                            append("** | uri=")
                            append(resource.uri)
                            resource.description?.takeIf { it.isNotBlank() }?.let {
                                append(" | ")
                                append(it.trim())
                            }
                            resource.mimeType?.takeIf { it.isNotBlank() }?.let {
                                append(" | mimeType=")
                                append(it)
                            }
                            resource.size?.let {
                                append(" | size=")
                                append(it)
                            }
                            appendLine()
                        }
                    }.trim(),
                )
            }
        }.getOrElse { exception ->
            ToolExecutionResult(
                content = "Failed to list MCP resources for \"${resolved.name}\": ${exception.message ?: "unknown error"}",
                success = false,
                recoveryHint = "Use mcp_describe_server first to confirm the server transport and command. If it is stdio, verify the command starts successfully in this project.",
            )
        }
    }

    fun readResource(projectRoot: Path, serverName: String, resourceUri: String): ToolExecutionResult {
        val resolved = resolveServer(projectRoot, serverName) ?: return missingServerResult(projectRoot, serverName)
        if (!supportsRuntimeTransport(resolved.transport)) {
            return unsupportedTransport(
                resolved,
                "resource reads",
                "Choose a stdio or streamable HTTP MCP server first, then use mcp_list_resources before trying to read one resource.",
            )
        }
        val normalizedUri = resourceUri.trim()
        if (normalizedUri.isBlank()) {
            return ToolExecutionResult(
                content = "Missing MCP resource uri parameter.",
                success = false,
                recoveryHint = "Call mcp_list_resources first and retry with one exact resource uri.",
            )
        }
        return runCatching {
            val resources = cachedOrFetchResources(resolved, projectRoot, refresh = false)
            val matchedResource = resources.firstOrNull { it.uri == normalizedUri }
                ?: return ToolExecutionResult(
                    content = "MCP resource \"$normalizedUri\" is not exposed by server \"${resolved.name}\". Available resources: ${resources.joinToString(", ") { it.uri }.ifBlank { "none" }}",
                    success = false,
                    recoveryHint = "Re-run mcp_list_resources and retry with one exact resource uri from that list.",
                )
            val contents = withSession(resolved, projectRoot) { session ->
                session.readResource(matchedResource.uri)
            }
            ToolExecutionResult(formatResourceContents(resolved.name, matchedResource.uri, contents))
        }.getOrElse { exception ->
            ToolExecutionResult(
                content = "Failed to read MCP resource \"$normalizedUri\" on \"${resolved.name}\": ${exception.message ?: "unknown error"}",
                success = false,
                recoveryHint = "Use mcp_list_resources again to confirm the exact resource uri before retrying.",
            )
        }
    }

    fun listResourceTemplates(projectRoot: Path, serverName: String): ToolExecutionResult {
        val resolved = resolveServer(projectRoot, serverName) ?: return missingServerResult(projectRoot, serverName)
        if (!supportsRuntimeTransport(resolved.transport)) {
            return unsupportedTransport(
                resolved,
                "resource templates",
                "Choose a stdio or streamable HTTP MCP server first, then use mcp_list_resource_templates to inspect parameterized resources.",
            )
        }
        return runCatching {
            val templates = cachedOrFetchResourceTemplates(resolved, projectRoot, refresh = false)
            if (templates.isEmpty()) {
                ToolExecutionResult("MCP server \"${resolved.name}\" returned no resource templates.")
            } else {
                ToolExecutionResult(
                    buildString {
                        appendLine("## MCP Resource Templates (${resolved.name})")
                        templates.forEach { template ->
                            append("- **")
                            append(template.name)
                            append("** | uriTemplate=")
                            append(template.uriTemplate)
                            template.description?.takeIf { it.isNotBlank() }?.let {
                                append(" | ")
                                append(it.trim())
                            }
                            template.mimeType?.takeIf { it.isNotBlank() }?.let {
                                append(" | mimeType=")
                                append(it)
                            }
                            appendLine()
                        }
                    }.trim(),
                )
            }
        }.getOrElse { exception ->
            ToolExecutionResult(
                content = "Failed to list MCP resource templates for \"${resolved.name}\": ${exception.message ?: "unknown error"}",
                success = false,
                recoveryHint = "Use mcp_describe_server first to confirm the server transport and command. If it is stdio, verify the command starts successfully in this project.",
            )
        }
    }

    fun listPrompts(projectRoot: Path, serverName: String): ToolExecutionResult {
        val resolved = resolveServer(projectRoot, serverName) ?: return missingServerResult(projectRoot, serverName)
        if (!supportsRuntimeTransport(resolved.transport)) {
            return unsupportedTransport(
                resolved,
                "prompts",
                "Choose a stdio or streamable HTTP MCP server first, then use mcp_list_prompts before trying to load one prompt.",
            )
        }
        return runCatching {
            val prompts = cachedOrFetchPrompts(resolved, projectRoot, refresh = false)
            if (prompts.isEmpty()) {
                ToolExecutionResult("MCP server \"${resolved.name}\" returned no prompts.")
            } else {
                ToolExecutionResult(
                    buildString {
                        appendLine("## MCP Prompts (${resolved.name})")
                        prompts.forEach { prompt ->
                            append("- **")
                            append(prompt.name)
                            append("**")
                            prompt.description?.takeIf { it.isNotBlank() }?.let {
                                append(": ")
                                append(it.trim())
                            }
                            if (prompt.arguments.isNotEmpty()) {
                                append(" | arguments=")
                                append(
                                    prompt.arguments.joinToString(", ") { argument ->
                                        if (argument.required) "${argument.name}*" else argument.name
                                    },
                                )
                            }
                            appendLine()
                        }
                    }.trim(),
                )
            }
        }.getOrElse { exception ->
            ToolExecutionResult(
                content = "Failed to list MCP prompts for \"${resolved.name}\": ${exception.message ?: "unknown error"}",
                success = false,
                recoveryHint = "Use mcp_describe_server first to confirm the server transport and command. If it is stdio, verify the command starts successfully in this project.",
            )
        }
    }

    fun getPrompt(projectRoot: Path, serverName: String, promptName: String, arguments: JsonNode): ToolExecutionResult {
        val resolved = resolveServer(projectRoot, serverName) ?: return missingServerResult(projectRoot, serverName)
        if (!supportsRuntimeTransport(resolved.transport)) {
            return unsupportedTransport(
                resolved,
                "prompt retrieval",
                "Choose a stdio or streamable HTTP MCP server first, then use mcp_list_prompts before trying to load one prompt.",
            )
        }
        if (promptName.isBlank()) {
            return ToolExecutionResult(
                content = "Missing MCP prompt name parameter.",
                success = false,
                recoveryHint = "Call mcp_list_prompts first and retry with one exact prompt name.",
            )
        }
        return runCatching {
            val prompts = cachedOrFetchPrompts(resolved, projectRoot, refresh = false)
            val matchedPrompt = prompts.firstOrNull { it.name.equals(promptName.trim(), ignoreCase = true) }
                ?: return ToolExecutionResult(
                    content = "MCP prompt \"$promptName\" is not exposed by server \"${resolved.name}\". Available prompts: ${prompts.joinToString(", ") { it.name }.ifBlank { "none" }}",
                    success = false,
                    recoveryHint = "Re-run mcp_list_prompts and retry with one exact prompt name from that list.",
                )
            val prompt = withSession(resolved, projectRoot) { session ->
                session.getPrompt(matchedPrompt.name, arguments)
            }
            ToolExecutionResult(formatPromptResult(resolved.name, matchedPrompt.name, prompt))
        }.getOrElse { exception ->
            ToolExecutionResult(
                content = "Failed to get MCP prompt \"$promptName\" on \"${resolved.name}\": ${exception.message ?: "unknown error"}",
                success = false,
                recoveryHint = "Use mcp_list_prompts to confirm the prompt exists, then narrow the prompt arguments before retrying.",
            )
        }
    }

    override fun close() {
        synchronized(this) {
            sessionCache.values.forEach { runCatching { it.session.close() } }
            sessionCache.clear()
            toolCache.clear()
            resourceCache.clear()
            resourceTemplateCache.clear()
            promptCache.clear()
        }
    }

    private fun resolveServer(projectRoot: Path, serverName: String): LoadedMcpServer? {
        val normalized = serverName.trim()
        if (normalized.isBlank()) return null
        return McpSupport.available(projectRoot).firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    private fun missingServerResult(projectRoot: Path, serverName: String): ToolExecutionResult {
        val available = McpSupport.available(projectRoot).joinToString(", ") { it.name }.ifBlank { "none" }
        return ToolExecutionResult(
            content = "MCP server \"${serverName.trim()}\" not found. Available servers: $available",
            success = false,
            recoveryHint = "Use mcp_list_servers first, then retry with one exact configured MCP server name.",
        )
    }

    private fun unsupportedTransport(
        server: LoadedMcpServer,
        capability: String,
        recoveryHint: String,
    ): ToolExecutionResult {
        return ToolExecutionResult(
            content = "MCP server \"${server.name}\" uses ${server.transport}, but $capability are currently supported only for stdio and streamable HTTP servers.",
            success = false,
            recoveryHint = recoveryHint,
        )
    }

    private fun supportsRuntimeTransport(transport: String): Boolean {
        return transport == "stdio" || transport == "http"
    }

    private fun cachedOrFetchTools(server: LoadedMcpServer, projectRoot: Path, refresh: Boolean): List<McpRemoteTool> {
        val key = cacheKey(server, projectRoot)
        val now = System.currentTimeMillis()
        synchronized(this) {
            expireLocked(now)
            if (!refresh) {
                val cached = toolCache[key]
                if (cached != null && now - cached.fetchedAt <= CATALOG_TTL_MS) {
                    return cached.tools
                }
            }
        }
        val tools = withSession(server, projectRoot) { session -> session.listTools() }
        synchronized(this) {
            toolCache[key] = CachedToolCatalog(tools = tools, fetchedAt = System.currentTimeMillis())
        }
        return tools
    }

    private fun cachedOrFetchResources(server: LoadedMcpServer, projectRoot: Path, refresh: Boolean): List<McpRemoteResource> {
        val key = cacheKey(server, projectRoot)
        val now = System.currentTimeMillis()
        synchronized(this) {
            expireLocked(now)
            if (!refresh) {
                val cached = resourceCache[key]
                if (cached != null && now - cached.fetchedAt <= CATALOG_TTL_MS) {
                    return cached.resources
                }
            }
        }
        val resources = withSession(server, projectRoot) { session -> session.listResources() }
        synchronized(this) {
            resourceCache[key] = CachedResourceCatalog(resources = resources, fetchedAt = System.currentTimeMillis())
        }
        return resources
    }

    private fun cachedOrFetchResourceTemplates(
        server: LoadedMcpServer,
        projectRoot: Path,
        refresh: Boolean,
    ): List<McpRemoteResourceTemplate> {
        val key = cacheKey(server, projectRoot)
        val now = System.currentTimeMillis()
        synchronized(this) {
            expireLocked(now)
            if (!refresh) {
                val cached = resourceTemplateCache[key]
                if (cached != null && now - cached.fetchedAt <= CATALOG_TTL_MS) {
                    return cached.templates
                }
            }
        }
        val templates = withSession(server, projectRoot) { session -> session.listResourceTemplates() }
        synchronized(this) {
            resourceTemplateCache[key] = CachedResourceTemplateCatalog(templates = templates, fetchedAt = System.currentTimeMillis())
        }
        return templates
    }

    private fun cachedOrFetchPrompts(server: LoadedMcpServer, projectRoot: Path, refresh: Boolean): List<McpRemotePrompt> {
        val key = cacheKey(server, projectRoot)
        val now = System.currentTimeMillis()
        synchronized(this) {
            expireLocked(now)
            if (!refresh) {
                val cached = promptCache[key]
                if (cached != null && now - cached.fetchedAt <= CATALOG_TTL_MS) {
                    return cached.prompts
                }
            }
        }
        val prompts = withSession(server, projectRoot) { session -> session.listPrompts() }
        synchronized(this) {
            promptCache[key] = CachedPromptCatalog(prompts = prompts, fetchedAt = System.currentTimeMillis())
        }
        return prompts
    }

    private fun <T> withSession(server: LoadedMcpServer, projectRoot: Path, action: (McpSession) -> T): T {
        val key = cacheKey(server, projectRoot)
        synchronized(this) {
            val now = System.currentTimeMillis()
            expireLocked(now)
            val cachedSession = sessionCache.getOrPut(key) {
                CachedSession(session = sessionFactory(server, projectRoot), lastUsedAt = now)
            }
            cachedSession.lastUsedAt = now
            return try {
                action(cachedSession.session)
            } catch (exception: Exception) {
                closeSessionLocked(key)
                throw exception
            }
        }
    }

    private fun expireLocked(now: Long) {
        val expiredSessions = sessionCache.filterValues { now - it.lastUsedAt > SESSION_TTL_MS }.keys.toList()
        expiredSessions.forEach(::closeSessionLocked)
        toolCache.entries.removeIf { now - it.value.fetchedAt > CATALOG_TTL_MS }
        resourceCache.entries.removeIf { now - it.value.fetchedAt > CATALOG_TTL_MS }
        resourceTemplateCache.entries.removeIf { now - it.value.fetchedAt > CATALOG_TTL_MS }
        promptCache.entries.removeIf { now - it.value.fetchedAt > CATALOG_TTL_MS }
    }

    private fun closeSessionLocked(key: String) {
        sessionCache.remove(key)?.let { cached ->
            runCatching { cached.session.close() }
        }
        toolCache.remove(key)
        resourceCache.remove(key)
        resourceTemplateCache.remove(key)
        promptCache.remove(key)
    }

    private fun cacheKey(server: LoadedMcpServer, projectRoot: Path): String {
        return listOf(
            projectRoot.toAbsolutePath().normalize().toString(),
            server.name.lowercase(),
            server.transport,
            server.command.orEmpty(),
            server.args.joinToString("\u0000"),
            server.url.orEmpty(),
        ).joinToString("|")
    }

    private fun formatResourceContents(serverName: String, resourceUri: String, contents: List<McpResourceContent>): String {
        if (contents.isEmpty()) {
            return "MCP resource \"$resourceUri\" on \"$serverName\" returned no contents."
        }
        return buildString {
            appendLine("## MCP Resource ($serverName)")
            contents.forEach { content ->
                appendLine("### ${content.uri.ifBlank { resourceUri }}")
                content.mimeType?.takeIf { it.isNotBlank() }?.let { appendLine("mimeType: $it") }
                when {
                    !content.text.isNullOrBlank() -> appendLine(content.text.trim())
                    !content.blob.isNullOrBlank() -> appendLine("[blob:${content.mimeType ?: "application/octet-stream"} base64Length=${content.blob.length}]")
                    else -> appendLine("[empty content]")
                }
                appendLine()
            }
        }.trim()
    }

    private fun formatPromptResult(serverName: String, promptName: String, prompt: McpPromptResult): String {
        return buildString {
            appendLine("## MCP Prompt (${serverName}/${promptName})")
            prompt.description?.takeIf { it.isNotBlank() }?.let {
                appendLine(it.trim())
                appendLine()
            }
            if (prompt.messages.isEmpty()) {
                appendLine("No prompt messages were returned.")
            } else {
                prompt.messages.forEachIndexed { index, message ->
                    appendLine("### ${index + 1}. ${message.role.ifBlank { "user" }}")
                    appendLine(formatTypedContent(message.content))
                    appendLine()
                }
            }
        }.trim()
    }

    private fun formatTypedContent(content: JsonNode): String {
        return when (content.path("type").asText()) {
            "text" -> content.path("text").asText().ifBlank { content.toString() }
            "image" -> "[image:${content.path("mimeType").asText("application/octet-stream")}]"
            "audio" -> "[audio:${content.path("mimeType").asText("application/octet-stream")}]"
            "resource" -> formatEmbeddedResource(content.path("resource"))
            else -> content.toString()
        }
    }

    private fun formatEmbeddedResource(resource: JsonNode): String {
        val uri = resource.path("uri").asText().ifBlank { "<resource>" }
        val mimeType = resource.path("mimeType").asText("").ifBlank { "application/octet-stream" }
        val text = resource.path("text").asText("").trim()
        val blob = resource.path("blob").asText("").trim()
        return when {
            text.isNotBlank() -> "[resource:$uri $mimeType]\n$text"
            blob.isNotBlank() -> "[resource:$uri $mimeType blobLength=${blob.length}]"
            else -> "[resource:$uri $mimeType]"
        }
    }

    private data class CachedSession(
        val session: McpSession,
        var lastUsedAt: Long,
    )

    private data class CachedToolCatalog(
        val tools: List<McpRemoteTool>,
        val fetchedAt: Long,
    )

    private data class CachedResourceCatalog(
        val resources: List<McpRemoteResource>,
        val fetchedAt: Long,
    )

    private data class CachedResourceTemplateCatalog(
        val templates: List<McpRemoteResourceTemplate>,
        val fetchedAt: Long,
    )

    private data class CachedPromptCatalog(
        val prompts: List<McpRemotePrompt>,
        val fetchedAt: Long,
    )

    private class HttpMcpSession(
        private val server: LoadedMcpServer,
        private val mapper: ObjectMapper,
    ) : McpSession {
        private val client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .build()
        private val endpoint = URI.create(server.url ?: error("MCP server ${server.name} does not define a URL."))
        private val nextId = AtomicInteger(1)
        private var sessionId: String? = null

        init {
            initialize()
        }

        override fun listTools(): List<McpRemoteTool> {
            val tools = mutableListOf<McpRemoteTool>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "tools/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("tools").takeIf { it.isArray }?.forEach { tool ->
                    tools += McpRemoteTool(
                        name = tool.path("name").asText(),
                        description = tool.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        inputSchema = tool.path("inputSchema").takeIf { !it.isMissingNode && !it.isNull },
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return tools
        }

        override fun callTool(name: String, arguments: JsonNode): McpCallResult {
            val result = request(
                method = "tools/call",
                params = mapOf(
                    "name" to name,
                    "arguments" to mapper.convertValue(arguments, Any::class.java),
                ),
            ).path("result")
            return McpCallResult(
                content = formatHttpToolResult(result),
                isError = result.path("isError").asBoolean(false),
            )
        }

        override fun listResources(): List<McpRemoteResource> {
            val resources = mutableListOf<McpRemoteResource>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "resources/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("resources").takeIf { it.isArray }?.forEach { resource ->
                    resources += McpRemoteResource(
                        uri = resource.path("uri").asText(),
                        name = resource.path("name").asText(),
                        description = resource.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        mimeType = resource.path("mimeType").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        size = resource.path("size").takeIf { it.isNumber }?.asLong(),
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return resources
        }

        override fun readResource(uri: String): List<McpResourceContent> {
            val result = request(
                method = "resources/read",
                params = mapOf("uri" to uri),
            ).path("result")
            return result.path("contents").takeIf { it.isArray }?.map { content ->
                McpResourceContent(
                    uri = content.path("uri").asText(uri),
                    mimeType = content.path("mimeType").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    text = content.path("text").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    blob = content.path("blob").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                )
            }.orEmpty()
        }

        override fun listResourceTemplates(): List<McpRemoteResourceTemplate> {
            val templates = mutableListOf<McpRemoteResourceTemplate>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "resources/templates/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("resourceTemplates").takeIf { it.isArray }?.forEach { template ->
                    templates += McpRemoteResourceTemplate(
                        uriTemplate = template.path("uriTemplate").asText(),
                        name = template.path("name").asText(),
                        description = template.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        mimeType = template.path("mimeType").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return templates
        }

        override fun listPrompts(): List<McpRemotePrompt> {
            val prompts = mutableListOf<McpRemotePrompt>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "prompts/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("prompts").takeIf { it.isArray }?.forEach { prompt ->
                    prompts += McpRemotePrompt(
                        name = prompt.path("name").asText(),
                        description = prompt.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        arguments = prompt.path("arguments").takeIf { it.isArray }?.map { argument ->
                            McpPromptArgument(
                                name = argument.path("name").asText(),
                                description = argument.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                                required = argument.path("required").asBoolean(false),
                            )
                        }.orEmpty(),
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return prompts
        }

        override fun getPrompt(name: String, arguments: JsonNode): McpPromptResult {
            val result = request(
                method = "prompts/get",
                params = buildMap<String, Any> {
                    put("name", name)
                    if (arguments.isObject && arguments.size() > 0) {
                        put("arguments", mapper.convertValue(arguments, Any::class.java))
                    }
                },
            ).path("result")
            return McpPromptResult(
                description = result.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                messages = result.path("messages").takeIf { it.isArray }?.map { message ->
                    McpPromptMessage(
                        role = message.path("role").asText("user"),
                        content = message.path("content"),
                    )
                }.orEmpty(),
            )
        }

        override fun close() {
            val currentSessionId = sessionId ?: return
            sessionId = null
            runCatching {
                val request = HttpRequest.newBuilder(endpoint)
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "IDopen/1.0-SNAPSHOT")
                    .header("Mcp-Session-Id", currentSessionId)
                    .apply {
                        server.headers.forEach { (key, value) ->
                            header(key, value)
                        }
                    }
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .build()
                client.send(request, HttpResponse.BodyHandlers.discarding())
            }
        }

        private fun initialize() {
            val response = sendJsonRpc(
                method = "initialize",
                params = mapOf(
                    "protocolVersion" to "2025-03-26",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf(
                        "name" to "IDopen",
                        "version" to "1.0-SNAPSHOT",
                    ),
                ),
                includeSession = false,
                allowReinitialize = false,
            )
            if (!response.path("result").isObject) {
                error("MCP initialize failed for ${server.name}: ${response.toString().take(400)}")
            }
            postNotification("notifications/initialized")
        }

        private fun request(method: String, params: Any? = null): JsonNode {
            return sendJsonRpc(
                method = method,
                params = params,
                includeSession = true,
                allowReinitialize = true,
            )
        }

        private fun postNotification(method: String, allowReinitialize: Boolean = true) {
            sendPayload(
                payload = mapOf(
                    "jsonrpc" to "2.0",
                    "method" to method,
                ),
                methodName = method,
                includeSession = true,
                allowReinitialize = allowReinitialize,
                expectResponse = false,
            )
        }

        private fun sendJsonRpc(
            method: String,
            params: Any? = null,
            includeSession: Boolean,
            allowReinitialize: Boolean,
        ): JsonNode {
            val id = nextId.getAndIncrement().toString()
            val payload = buildMap<String, Any?> {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) {
                    put("params", params)
                }
            }
            return sendPayload(
                payload = payload,
                methodName = method,
                includeSession = includeSession,
                allowReinitialize = allowReinitialize,
                expectResponse = true,
                requestId = id,
            ) ?: error("MCP $method returned no response body.")
        }

        private fun sendPayload(
            payload: Any,
            methodName: String,
            includeSession: Boolean,
            allowReinitialize: Boolean,
            expectResponse: Boolean,
            requestId: String? = null,
        ): JsonNode? {
            val requestBody = mapper.writeValueAsString(payload)
            val request = baseRequestBuilder(includeSession)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            response.headers().firstValue("Mcp-Session-Id").ifPresent { headerValue ->
                sessionId = headerValue.takeIf { it.isNotBlank() }
            }
            if (response.statusCode() == 404 && includeSession && sessionId != null && allowReinitialize) {
                sessionId = null
                initialize()
                return sendPayload(payload, methodName, includeSession = true, allowReinitialize = false, expectResponse = expectResponse, requestId = requestId)
            }
            if (response.statusCode() !in 200..299) {
                val detail = response.body().trim().take(400)
                error("MCP $methodName failed: HTTP ${response.statusCode()} ${detail}".trim())
            }
            if (!expectResponse) {
                return null
            }
            return parseHttpResponse(methodName, response.body(), response.headers().firstValue("Content-Type").orElse(""), requestId)
        }

        private fun parseHttpResponse(methodName: String, body: String, contentType: String, requestId: String?): JsonNode {
            val trimmed = body.trim()
            if (trimmed.isBlank()) {
                error("MCP $methodName returned an empty response body.")
            }
            val isSse = contentType.contains("text/event-stream", ignoreCase = true) ||
                trimmed.startsWith("event:") ||
                trimmed.startsWith("data:")
            return if (isSse) {
                parseSseResponse(methodName, trimmed, requestId)
            } else {
                parseJsonResponse(methodName, trimmed, requestId)
            }
        }

        private fun parseJsonResponse(methodName: String, body: String, requestId: String?): JsonNode {
            val node = mapper.readTree(body)
            findMatchingJsonRpcMessage(node, requestId)?.let { return it }
            error("MCP $methodName returned no matching JSON-RPC response for request id $requestId.")
        }

        private fun parseSseResponse(methodName: String, body: String, requestId: String?): JsonNode {
            val eventData = mutableListOf<String>()
            body.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd()
                when {
                    line.isBlank() -> {
                        extractSseJson(eventData, requestId)?.let { return it }
                        eventData.clear()
                    }
                    line.startsWith("data:") -> eventData += line.removePrefix("data:").trimStart()
                }
            }
            extractSseJson(eventData, requestId)?.let { return it }
            error("MCP $methodName returned SSE without a matching JSON-RPC response for request id $requestId.")
        }

        private fun extractSseJson(lines: List<String>, requestId: String?): JsonNode? {
            if (lines.isEmpty()) return null
            val payload = lines.joinToString("\n").trim()
            if (payload.isBlank() || payload == "[DONE]") return null
            val node = mapper.readTree(payload)
            return findMatchingJsonRpcMessage(node, requestId)
        }

        private fun findMatchingJsonRpcMessage(node: JsonNode, requestId: String?): JsonNode? {
            if (node.isArray) {
                node.forEach { child ->
                    findMatchingJsonRpcMessage(child, requestId)?.let { return it }
                }
                return null
            }
            if (!node.isObject) return null
            if (requestId == null) return node
            return if (node.path("id").asText() == requestId) node else null
        }

        private fun baseRequestBuilder(includeSession: Boolean): HttpRequest.Builder {
            return HttpRequest.newBuilder(endpoint)
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("User-Agent", "IDopen/1.0-SNAPSHOT")
                .apply {
                    if (includeSession) {
                        sessionId?.takeIf { it.isNotBlank() }?.let { header("Mcp-Session-Id", it) }
                    }
                    server.headers.forEach { (key, value) ->
                        header(key, value)
                    }
                }
        }

        private fun formatHttpToolResult(result: JsonNode): String {
            val content = result.path("content")
            if (!content.isArray || content.size() == 0) {
                return result.toString()
            }
            return content.mapNotNull { item ->
                when (item.path("type").asText()) {
                    "text" -> item.path("text").asText().takeIf { it.isNotBlank() }
                    "image" -> "[image:${item.path("mimeType").asText("application/octet-stream")}]"
                    "audio" -> "[audio:${item.path("mimeType").asText("application/octet-stream")}]"
                    "resource" -> {
                        val resource = item.path("resource")
                        val uri = resource.path("uri").asText().ifBlank { "<resource>" }
                        val mimeType = resource.path("mimeType").asText("").ifBlank { "application/octet-stream" }
                        val text = resource.path("text").asText("").trim()
                        val blob = resource.path("blob").asText("").trim()
                        when {
                            text.isNotBlank() -> "[resource:$uri $mimeType]\n$text"
                            blob.isNotBlank() -> "[resource:$uri $mimeType blobLength=${blob.length}]"
                            else -> "[resource:$uri $mimeType]"
                        }
                    }
                    else -> item.toString()
                }
            }.joinToString("\n\n").ifBlank { result.toString() }
        }
    }

    private class StdioMcpSession(
        private val server: LoadedMcpServer,
        private val projectRoot: Path,
        private val mapper: ObjectMapper,
    ) : McpSession {
        private val nextId = AtomicInteger(1)
        private val queue = LinkedBlockingQueue<JsonNode>()
        private val stderrLines = ArrayDeque<String>()
        private val process = ProcessBuilder(buildCommand(server))
            .directory(projectRoot.toFile())
            .apply {
                environment().putAll(server.env)
            }
            .start()
        private val writer = process.outputStream.writer(StandardCharsets.UTF_8).buffered()

        init {
            thread(name = "idopen-mcp-stdout-${server.name}", isDaemon = true) {
                readStdout(process.inputStream)
            }
            thread(name = "idopen-mcp-stderr-${server.name}", isDaemon = true) {
                readStderr(process.errorStream)
            }
            initialize()
        }

        override fun listTools(): List<McpRemoteTool> {
            val tools = mutableListOf<McpRemoteTool>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "tools/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("tools").takeIf { it.isArray }?.forEach { tool ->
                    tools += McpRemoteTool(
                        name = tool.path("name").asText(),
                        description = tool.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        inputSchema = tool.path("inputSchema").takeIf { !it.isMissingNode && !it.isNull },
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return tools
        }

        override fun callTool(name: String, arguments: JsonNode): McpCallResult {
            val result = request(
                method = "tools/call",
                params = mapOf(
                    "name" to name,
                    "arguments" to mapper.convertValue(arguments, Any::class.java),
                ),
            ).path("result")
            return McpCallResult(
                content = formatToolResult(result),
                isError = result.path("isError").asBoolean(false),
            )
        }

        override fun listResources(): List<McpRemoteResource> {
            val resources = mutableListOf<McpRemoteResource>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "resources/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("resources").takeIf { it.isArray }?.forEach { resource ->
                    resources += McpRemoteResource(
                        uri = resource.path("uri").asText(),
                        name = resource.path("name").asText(),
                        description = resource.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        mimeType = resource.path("mimeType").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        size = resource.path("size").takeIf { it.isNumber }?.asLong(),
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return resources
        }

        override fun readResource(uri: String): List<McpResourceContent> {
            val result = request(
                method = "resources/read",
                params = mapOf("uri" to uri),
            ).path("result")
            return result.path("contents").takeIf { it.isArray }?.map { content ->
                McpResourceContent(
                    uri = content.path("uri").asText(uri),
                    mimeType = content.path("mimeType").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    text = content.path("text").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    blob = content.path("blob").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                )
            }.orEmpty()
        }

        override fun listResourceTemplates(): List<McpRemoteResourceTemplate> {
            val templates = mutableListOf<McpRemoteResourceTemplate>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "resources/templates/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("resourceTemplates").takeIf { it.isArray }?.forEach { template ->
                    templates += McpRemoteResourceTemplate(
                        uriTemplate = template.path("uriTemplate").asText(),
                        name = template.path("name").asText(),
                        description = template.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        mimeType = template.path("mimeType").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return templates
        }

        override fun listPrompts(): List<McpRemotePrompt> {
            val prompts = mutableListOf<McpRemotePrompt>()
            var cursor: String? = null
            do {
                val result = request(
                    method = "prompts/list",
                    params = cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>(),
                ).path("result")
                result.path("prompts").takeIf { it.isArray }?.forEach { prompt ->
                    prompts += McpRemotePrompt(
                        name = prompt.path("name").asText(),
                        description = prompt.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                        arguments = prompt.path("arguments").takeIf { it.isArray }?.map { argument ->
                            McpPromptArgument(
                                name = argument.path("name").asText(),
                                description = argument.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                                required = argument.path("required").asBoolean(false),
                            )
                        }.orEmpty(),
                    )
                }
                cursor = result.path("nextCursor").asText("").ifBlank { null }
            } while (cursor != null)
            return prompts
        }

        override fun getPrompt(name: String, arguments: JsonNode): McpPromptResult {
            val result = request(
                method = "prompts/get",
                params = buildMap<String, Any> {
                    put("name", name)
                    if (arguments.isObject && arguments.size() > 0) {
                        put("arguments", mapper.convertValue(arguments, Any::class.java))
                    }
                },
            ).path("result")
            return McpPromptResult(
                description = result.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                messages = result.path("messages").takeIf { it.isArray }?.map { message ->
                    McpPromptMessage(
                        role = message.path("role").asText("user"),
                        content = message.path("content"),
                    )
                }.orEmpty(),
            )
        }

        override fun close() {
            runCatching { writer.close() }
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
        }

        private fun initialize() {
            val response = request(
                method = "initialize",
                params = mapOf(
                    "protocolVersion" to "2025-03-26",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf(
                        "name" to "IDopen",
                        "version" to "1.0-SNAPSHOT",
                    ),
                ),
            )
            if (!response.path("result").isObject) {
                error("MCP initialize failed for ${server.name}: ${response.toString().take(400)}")
            }
            notify("notifications/initialized")
        }

        private fun request(method: String, params: Any? = null): JsonNode {
            val id = nextId.getAndIncrement().toString()
            writeJson(
                buildMap<String, Any?> {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    if (params != null) {
                        put("params", params)
                    }
                },
            )
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadlineNanos) {
                val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                val message = queue.poll(remainingMs, TimeUnit.MILLISECONDS) ?: continue
                if (!message.path("id").isMissingNode && message.path("id").asText() == id) {
                    if (message.path("error").isObject) {
                        val error = message.path("error")
                        error("MCP $method failed: ${error.path("message").asText("unknown error")} ${stderrSummary()}".trim())
                    }
                    return message
                }
            }
            error("Timed out waiting for MCP $method response from ${server.name}. ${stderrSummary()}".trim())
        }

        private fun notify(method: String) {
            writeJson(
                mapOf(
                    "jsonrpc" to "2.0",
                    "method" to method,
                ),
            )
        }

        private fun writeJson(payload: Any) {
            val line = mapper.writeValueAsString(payload)
            writer.write(line)
            writer.newLine()
            writer.flush()
        }

        private fun readStdout(stream: InputStream) {
            InputStreamReader(stream, StandardCharsets.UTF_8).buffered().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@forEach
                    val node = runCatching { mapper.readTree(trimmed) }.getOrNull() ?: return@forEach
                    queue.offer(node)
                }
            }
        }

        private fun readStderr(stream: InputStream) {
            InputStreamReader(stream, StandardCharsets.UTF_8).buffered().useLines { lines ->
                lines.forEach { line ->
                    synchronized(stderrLines) {
                        if (stderrLines.size >= 12) {
                            stderrLines.removeFirst()
                        }
                        stderrLines.addLast(line.trim())
                    }
                }
            }
        }

        private fun stderrSummary(): String {
            val summary = synchronized(stderrLines) { stderrLines.filter { it.isNotBlank() }.joinToString(" | ") }
            return if (summary.isBlank()) "" else "stderr: ${summary.take(280)}"
        }

        private fun formatToolResult(result: JsonNode): String {
            val content = result.path("content")
            if (!content.isArray || content.size() == 0) {
                return result.toString()
            }
            return content.mapNotNull { item ->
                when (item.path("type").asText()) {
                    "text" -> item.path("text").asText().takeIf { it.isNotBlank() }
                    "image" -> "[image:${item.path("mimeType").asText("application/octet-stream")}]"
                    "audio" -> "[audio:${item.path("mimeType").asText("application/octet-stream")}]"
                    "resource" -> formatToolEmbeddedResource(item.path("resource"))
                    else -> item.toString()
                }
            }.joinToString("\n\n").ifBlank { result.toString() }
        }

        private fun formatToolEmbeddedResource(resource: JsonNode): String {
            val uri = resource.path("uri").asText().ifBlank { "<resource>" }
            val mimeType = resource.path("mimeType").asText("").ifBlank { "application/octet-stream" }
            val text = resource.path("text").asText("").trim()
            val blob = resource.path("blob").asText("").trim()
            return when {
                text.isNotBlank() -> "[resource:$uri $mimeType]\n$text"
                blob.isNotBlank() -> "[resource:$uri $mimeType blobLength=${blob.length}]"
                else -> "[resource:$uri $mimeType]"
            }
        }

        private fun buildCommand(server: LoadedMcpServer): List<String> {
            val executable = server.command?.trim().orEmpty()
            require(executable.isNotBlank()) { "MCP server ${server.name} does not define a command." }
            return buildList {
                add(executable)
                addAll(server.args)
            }
        }
    }
}
