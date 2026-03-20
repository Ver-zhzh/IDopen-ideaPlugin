package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class IntelliJAgentTools(
    private val project: Project,
    private val shellSettings: () -> com.idopen.idopen.settings.IDopenSettingsState,
    private val approvalRequester: (ApprovalRequest) -> CompletableFuture<Boolean>,
    private val todoReader: () -> List<SessionTodoItem> = { emptyList() },
    private val todoWriter: (List<SessionTodoItem>) -> Unit = {},
    private val mcpRuntime: McpRuntimeSupport = McpRuntimeSupport(),
) {
    private val mapper = ObjectMapper()
    private val projectRoot = Paths.get(project.basePath ?: ".").toAbsolutePath().normalize()

    fun definitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            id = "mcp_list_servers",
            description = McpSupport.toolDescription(projectRoot),
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
        ),
        ToolDefinition(
            id = "mcp_describe_server",
            description = "Describe one configured MCP server by name, including its scope, transport, command or URL, and visible keys.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("name"),
                "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "mcp_list_tools",
            description = "Connect to one supported MCP server and list the tools it exposes.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("server"),
                "properties" to mapOf(
                    "server" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "mcp_call_tool",
            description = "Call one tool from a configured supported MCP server. Use mcp_list_tools first to confirm the exact tool name and schema.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("server", "tool"),
                "properties" to mapOf(
                    "server" to mapOf("type" to "string"),
                    "tool" to mapOf("type" to "string"),
                    "arguments" to mapOf("type" to "object"),
                ),
            ),
        ),
        ToolDefinition(
            id = "mcp_list_resources",
            description = "Connect to one supported MCP server and list the resources it exposes.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("server"),
                "properties" to mapOf(
                    "server" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "mcp_read_resource",
            description = "Read one listed resource from a configured supported MCP server. Use mcp_list_resources first to confirm the exact uri.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("server", "uri"),
                "properties" to mapOf(
                    "server" to mapOf("type" to "string"),
                    "uri" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "mcp_list_resource_templates",
            description = "Connect to one supported MCP server and list the parameterized resource templates it exposes.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("server"),
                "properties" to mapOf(
                    "server" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "mcp_list_prompts",
            description = "Connect to one supported MCP server and list the prompts it exposes.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("server"),
                "properties" to mapOf(
                    "server" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "mcp_get_prompt",
            description = "Load one prompt from a configured supported MCP server. Use mcp_list_prompts first to confirm the exact prompt name and arguments.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("server", "name"),
                "properties" to mapOf(
                    "server" to mapOf("type" to "string"),
                    "name" to mapOf("type" to "string"),
                    "arguments" to mapOf("type" to "object"),
                ),
            ),
        ),
        ToolDefinition(
            id = "skill",
            description = SkillSupport.toolDescription(projectRoot),
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("name"),
                "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "todo_read",
            description = "Read the current ordered todo list for this chat session.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
        ),
        ToolDefinition(
            id = "todo_write",
            description = "Replace the current ordered todo list for this chat session. Use statuses pending, in_progress, or completed.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("todos"),
                "properties" to mapOf(
                    "todos" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "required" to listOf("content", "status"),
                            "properties" to mapOf(
                                "content" to mapOf("type" to "string"),
                                "status" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("pending", "in_progress", "completed"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            id = "read_project_tree",
            description = "List files and directories in the current IntelliJ project.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "maxDepth" to mapOf("type" to "integer", "default" to 4),
                    "maxEntries" to mapOf("type" to "integer", "default" to 200),
                ),
            ),
        ),
        ToolDefinition(
            id = "read_file",
            description = "Read a file or directory from the current project. Prefer offset/limit over full-file reads.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("path"),
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "offset" to mapOf("type" to "integer", "minimum" to 1),
                    "limit" to mapOf("type" to "integer", "minimum" to 1),
                    "startLine" to mapOf("type" to "integer", "minimum" to 1),
                    "endLine" to mapOf("type" to "integer", "minimum" to 1),
                ),
            ),
        ),
        ToolDefinition(
            id = "search_text",
            description = "Search plain text across project files.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("query"),
                "properties" to mapOf(
                    "query" to mapOf("type" to "string"),
                    "maxResults" to mapOf("type" to "integer", "default" to 20),
                ),
            ),
        ),
        ToolDefinition(
            id = "get_current_file",
            description = "Read the currently focused editor file.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
        ),
        ToolDefinition(
            id = "get_current_selection",
            description = "Read the current editor selection.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
        ),
        ToolDefinition(
            id = "apply_patch_preview",
            description = "Preview and apply a file change. Use edits for focused changes or newContent for full rewrites.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("filePath"),
                "properties" to mapOf(
                    "filePath" to mapOf("type" to "string"),
                    "newContent" to mapOf("type" to "string"),
                    "edits" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "search" to mapOf("type" to "string"),
                                "replace" to mapOf("type" to "string"),
                                "occurrence" to mapOf("type" to "integer", "minimum" to 1),
                                "replaceAll" to mapOf("type" to "boolean"),
                                "before" to mapOf("type" to "string"),
                                "after" to mapOf("type" to "string"),
                                "startLine" to mapOf("type" to "integer", "minimum" to 1),
                                "endLine" to mapOf("type" to "integer", "minimum" to 0),
                                "newText" to mapOf("type" to "string"),
                            ),
                        ),
                    ),
                    "explanation" to mapOf("type" to "string"),
                ),
            ),
        ),
        ToolDefinition(
            id = "run_command",
            description = "Run a shell command inside the current project root or a subdirectory.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("command"),
                "properties" to mapOf(
                    "command" to mapOf("type" to "string"),
                    "workingDirectory" to mapOf("type" to "string"),
                ),
            ),
        ),
    )

    fun execute(
        call: ToolCall,
        observer: ToolExecutionObserver = ToolExecutionObserver {},
    ): ToolExecutionResult {
        val args = mapper.readTree(call.argumentsJson)
        return when (call.name) {
            "mcp_list_servers" -> listMcpServers(observer)
            "mcp_describe_server" -> describeMcpServer(args.path("name").asText(""), observer)
            "mcp_list_tools" -> listMcpTools(args.path("server").asText(""), observer)
            "mcp_call_tool" -> callMcpTool(
                serverName = args.path("server").asText(""),
                toolName = args.path("tool").asText(""),
                arguments = args.path("arguments").takeIf { it.isObject } ?: mapper.createObjectNode(),
                observer = observer,
            )
            "mcp_list_resources" -> listMcpResources(args.path("server").asText(""), observer)
            "mcp_read_resource" -> readMcpResource(
                serverName = args.path("server").asText(""),
                resourceUri = args.path("uri").asText(""),
                observer = observer,
            )
            "mcp_list_resource_templates" -> listMcpResourceTemplates(args.path("server").asText(""), observer)
            "mcp_list_prompts" -> listMcpPrompts(args.path("server").asText(""), observer)
            "mcp_get_prompt" -> getMcpPrompt(
                serverName = args.path("server").asText(""),
                promptName = args.path("name").asText(""),
                arguments = args.path("arguments").takeIf { it.isObject } ?: mapper.createObjectNode(),
                observer = observer,
            )
            "skill" -> loadSkill(args.path("name").asText(""), observer)
            "todo_read" -> readTodos(observer)
            "todo_write" -> writeTodos(args.path("todos"), observer)
            "read_project_tree" -> readProjectTree(args.path("maxDepth").asInt(4), args.path("maxEntries").asInt(200), observer)
            "read_file" -> readFile(
                path = args.path("path").asText(""),
                offset = args.optionalInt("offset"),
                limit = args.optionalInt("limit"),
                startLine = args.optionalInt("startLine"),
                endLine = args.optionalInt("endLine"),
                observer = observer,
            )
            "search_text" -> searchText(args.path("query").asText(""), args.path("maxResults").asInt(20), observer)
            "get_current_file" -> getCurrentFile(observer)
            "get_current_selection" -> getCurrentSelection(observer)
            "apply_patch_preview" -> applyPatchPreview(
                filePath = args.path("filePath").asText(""),
                newContent = args.optionalText("newContent"),
                edits = args.path("edits").takeIf { it.isArray }?.let(::parsePatchEdits).orEmpty(),
                explanation = args.path("explanation").asText(""),
                observer = observer,
            )
            "run_command" -> runCommand(
                command = args.path("command").asText(""),
                workingDirectory = args.optionalText("workingDirectory"),
                observer = observer,
            )
            else -> ToolExecutionResult("未知工具：${call.name}", success = false)
        }
    }

    private fun listMcpServers(observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "List MCP servers",
            ),
        )
        return McpSupport.listToolResult(projectRoot)
    }

    private fun describeMcpServer(name: String, observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "Describe MCP server",
                metadata = mapOf("name" to name.ifBlank { "<empty>" }),
            ),
        )
        return McpSupport.describeToolResult(projectRoot, name)
    }

    private fun listMcpTools(serverName: String, observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "List MCP tools",
                metadata = mapOf("server" to serverName.ifBlank { "<empty>" }),
            ),
        )
        return mcpRuntime.listTools(projectRoot, serverName)
    }

    private fun listMcpResources(serverName: String, observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "List MCP resources",
                metadata = mapOf("server" to serverName.ifBlank { "<empty>" }),
            ),
        )
        return mcpRuntime.listResources(projectRoot, serverName)
    }

    private fun readMcpResource(
        serverName: String,
        resourceUri: String,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "Read MCP resource",
                metadata = mapOf(
                    "server" to serverName.ifBlank { "<empty>" },
                    "uri" to resourceUri.ifBlank { "<empty>" },
                ),
            ),
        )
        return mcpRuntime.readResource(projectRoot, serverName, resourceUri)
    }

    private fun listMcpResourceTemplates(serverName: String, observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "List MCP resource templates",
                metadata = mapOf("server" to serverName.ifBlank { "<empty>" }),
            ),
        )
        return mcpRuntime.listResourceTemplates(projectRoot, serverName)
    }

    private fun listMcpPrompts(serverName: String, observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "List MCP prompts",
                metadata = mapOf("server" to serverName.ifBlank { "<empty>" }),
            ),
        )
        return mcpRuntime.listPrompts(projectRoot, serverName)
    }

    private fun getMcpPrompt(
        serverName: String,
        promptName: String,
        arguments: JsonNode,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "Get MCP prompt",
                metadata = mapOf(
                    "server" to serverName.ifBlank { "<empty>" },
                    "name" to promptName.ifBlank { "<empty>" },
                ),
            ),
        )
        return mcpRuntime.getPrompt(projectRoot, serverName, promptName, arguments)
    }

    private fun callMcpTool(
        serverName: String,
        toolName: String,
        arguments: JsonNode,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        if (serverName.isBlank()) {
            return ToolExecutionResult(
                content = "Missing MCP server name parameter.",
                success = false,
                recoveryHint = "Use mcp_list_servers first, then retry with one exact configured server name.",
            )
        }
        if (toolName.isBlank()) {
            return ToolExecutionResult(
                content = "Missing MCP tool name parameter.",
                success = false,
                recoveryHint = "Use mcp_list_tools first, then retry with one exact MCP tool name.",
            )
        }
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.PENDING,
                title = "Approve MCP tool call",
                metadata = mapOf(
                    "server" to serverName,
                    "tool" to toolName,
                ),
            ),
        )
        val request = ApprovalRequest(
            id = "approval-${System.nanoTime()}",
            type = ApprovalRequest.Type.COMMAND,
            title = "Invoke MCP tool $toolName on $serverName",
            payload = ApprovalPayload.Command(
                command = "mcp tools/call server=$serverName tool=$toolName arguments=${truncate(arguments.toString(), 600)}",
                workingDirectory = projectRoot.toString(),
            ),
        )
        val approved = approvalRequester(request).get()
        if (!approved) {
            return ToolExecutionResult(
                content = "The MCP tool call was rejected by the user.",
                success = false,
                recoveryHint = FailureRecoverySupport.approvalHint(request),
            )
        }
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "Call MCP tool",
                metadata = mapOf(
                    "server" to serverName,
                    "tool" to toolName,
                ),
            ),
        )
        return mcpRuntime.callTool(projectRoot, serverName, toolName, arguments)
    }

    private fun loadSkill(name: String, observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "Load skill",
                metadata = mapOf("name" to name.ifBlank { "<empty>" }),
            ),
        )
        return SkillSupport.loadToolResult(projectRoot, name)
    }

    private fun readTodos(observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "读取任务列表",
            ),
        )
        return ToolExecutionResult(SessionTodoSupport.formatForTool(todoReader()))
    }

    private fun writeTodos(todosNode: JsonNode, observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "更新任务列表",
            ),
        )
        val todos = SessionTodoSupport.parseTodos(todosNode)
        SessionTodoSupport.validateTodos(todos)?.let { validationError ->
            return ToolExecutionResult(
                content = validationError,
                success = false,
                recoveryHint = "Keep the todo list short and ordered, with at most one in_progress item.",
            )
        }
        todoWriter(todos)
        return ToolExecutionResult(SessionTodoSupport.summary(todos))
    }

    private fun readProjectTree(
        maxDepth: Int,
        maxEntries: Int,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "读取项目结构",
                metadata = mapOf("maxDepth" to maxDepth.toString(), "maxEntries" to maxEntries.toString()),
            ),
        )
        val output = StringBuilder()
        var appended = 0
        ReadAction.run<RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                appendTree(root, root, maxDepth.coerceAtLeast(1), output, canAppend = {
                    if (appended >= maxEntries.coerceAtLeast(1)) {
                        false
                    } else {
                        appended += 1
                        true
                    }
                })
            }
        }
        return ToolExecutionResult(
            if (output.isBlank()) "未找到项目内容。" else output.toString().trimEnd(),
        )
    }

    private fun appendTree(
        root: VirtualFile,
        file: VirtualFile,
        maxDepth: Int,
        output: StringBuilder,
        canAppend: () -> Boolean,
        depth: Int = 0,
    ) {
        if (depth > maxDepth || !canAppend()) return
        val relative = VfsUtil.getRelativePath(file, root, '/') ?: file.name
        output.append("  ".repeat(depth))
            .append(if (file.isDirectory) "[dir] " else "[file] ")
            .append(relative)
            .append('\n')
        if (!file.isDirectory || depth == maxDepth) return
        file.children.sortedBy { it.name }.forEach { child ->
            appendTree(root, child, maxDepth, output, canAppend, depth + 1)
        }
    }

    private fun readFile(
        path: String,
        offset: Int?,
        limit: Int?,
        startLine: Int?,
        endLine: Int?,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        validateReadRequest(path)?.let { return it }
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "读取文件",
                metadata = mapOf("path" to path),
            ),
        )
        if (path.isBlank()) return ToolExecutionResult("缺少 path 参数。", success = false)
        val resolved = resolveProjectPath(path) ?: return ToolExecutionResult("路径超出了当前项目范围。", success = false)
        if (!Files.exists(resolved)) return ToolExecutionResult("文件或目录不存在：$path", success = false)

        val request = ReadWindowSupport.normalizeRequest(
            offset = offset,
            limit = limit,
            startLine = startLine,
            endLine = endLine,
        )
        val relative = projectRoot.relativize(resolved).toString().replace('\\', '/')

        return when {
            Files.isDirectory(resolved) -> readDirectory(relative, resolved, request)
            isLikelyBinary(resolved) -> ToolExecutionResult("无法读取二进制文件：$path", success = false)
            else -> readTextFile(relative, resolved, request)
        }
    }

    private fun readDirectory(
        relative: String,
        directory: Path,
        request: ReadWindowSupport.WindowRequest,
    ): ToolExecutionResult {
        val entries = Files.list(directory).use { stream ->
            stream.map { child ->
                val name = child.fileName.toString()
                if (Files.isDirectory(child)) "$name/" else name
            }.sorted().toList()
        }
        return ToolExecutionResult(ReadWindowSupport.formatDirectory(relative, entries, request))
    }

    private fun readTextFile(
        relative: String,
        file: Path,
        request: ReadWindowSupport.WindowRequest,
    ): ToolExecutionResult {
        val lines = mutableListOf<String>()
        var totalLines = 0
        var hasMore = false

        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                totalLines += 1
                if (totalLines < request.offset) continue
                if (lines.size < request.limit) {
                    lines += line
                } else {
                    hasMore = true
                }
            }
        }

        if (request.offset > totalLines && !(request.offset == 1 && totalLines == 0)) {
            return ToolExecutionResult("offset=${request.offset} 超出了文件总行数 $totalLines。", success = false)
        }

        return ToolExecutionResult(
            ReadWindowSupport.formatFile(
                path = relative,
                slice = ReadWindowSupport.WindowSlice(
                    offset = request.offset,
                    lines = lines,
                    totalLines = totalLines,
                    hasMore = hasMore,
                ),
            ),
        )
    }

    private fun searchText(
        query: String,
        maxResults: Int,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        if (query.isBlank()) return ToolExecutionResult("缺少 query 参数。", success = false)
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "搜索项目文本",
                metadata = mapOf("query" to query, "maxResults" to maxResults.toString()),
            ),
        )
        val results = mutableListOf<String>()
        ReadAction.run<RuntimeException> {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (results.size >= maxResults) return@iterateContent false
                if (file.isDirectory || file.fileType.isBinary || file.length > 512_000) return@iterateContent true
                val text = runCatching { VfsUtil.loadText(file) }.getOrNull() ?: return@iterateContent true
                text.lineSequence().forEachIndexed { index, line ->
                    if (results.size >= maxResults) return@forEachIndexed
                    if (line.contains(query, ignoreCase = true)) {
                        results += "${relativePath(file)}:${index + 1}: ${line.trim()}"
                    }
                }
                true
            }
        }
        return ToolExecutionResult(
            if (results.isEmpty()) "没有找到包含 \"$query\" 的内容。" else results.joinToString("\n"),
        )
    }

    private fun getCurrentFile(observer: ToolExecutionObserver): ToolExecutionResult {
        observer.onUpdate(ToolProgressUpdate(state = ToolInvocationState.RUNNING, title = "读取当前文件"))
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return ToolExecutionResult("当前没有激活的编辑器。", success = false)
        val file = FileDocumentManager.getInstance().getFile(editor.document)
            ?: return ToolExecutionResult("当前没有激活的文件。", success = false)
        val lines = editor.document.text.lines()
        val slice = ReadWindowSupport.sliceLines(
            lines = lines,
            request = ReadWindowSupport.normalizeRequest(offset = 1, limit = ReadWindowSupport.DEFAULT_LIMIT),
        )
        return ToolExecutionResult(ReadWindowSupport.formatFile(relativePath(file), slice))
    }

    private fun getCurrentSelection(observer: ToolExecutionObserver): ToolExecutionResult {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return ToolExecutionResult("当前没有激活的编辑器。", success = false)
        observer.onUpdate(ToolProgressUpdate(state = ToolInvocationState.RUNNING, title = "读取当前选区"))
        val selection = editor.selectionModel.selectedText
            ?: return ToolExecutionResult("当前编辑器没有选中文本。", success = false)
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val startOffset = editor.selectionModel.selectionStart
        val endOffset = editor.selectionModel.selectionEnd
        val startLine = editor.document.getLineNumber(startOffset) + 1
        val endLine = editor.document.getLineNumber((endOffset - 1).coerceAtLeast(startOffset)) + 1
        return ToolExecutionResult(
            ReadWindowSupport.formatSelection(
                path = file?.let(::relativePath),
                startLine = startLine,
                endLine = endLine,
                content = selection,
            ),
        )
    }

    private fun applyPatchPreview(
        filePath: String,
        newContent: String?,
        edits: List<PatchEdit>,
        explanation: String,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        return applyPatchPreviewWithRecovery(filePath, newContent, edits, explanation, observer)
        validatePatchRequest(filePath)?.let { return it }
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.PENDING,
                title = "等待批准补丁",
                metadata = mapOf("file" to filePath),
            ),
        )
        if (filePath.isBlank()) return ToolExecutionResult("缺少 filePath 参数。", success = false)
        val resolved = resolveProjectPath(filePath) ?: return ToolExecutionResult("路径超出了当前项目范围。", success = false)
        val beforeText = if (Files.exists(resolved)) Files.readString(resolved, StandardCharsets.UTF_8) else ""
        val afterText = runCatching {
            when {
                !newContent.isNullOrBlank() -> newContent
                edits.isNotEmpty() -> PatchEditSupport.apply(beforeText, edits)
                else -> error("必须提供 newContent 或 edits。")
            }
        }.getOrElse { return ToolExecutionResult("补丁生成失败：${it.message}", success = false) }

        val request = ApprovalRequest(
            id = "approval-${System.nanoTime()}",
            type = ApprovalRequest.Type.PATCH,
            title = "写入文件 $filePath",
            payload = ApprovalPayload.Patch(
                filePath = filePath,
                beforeText = beforeText,
                afterText = afterText,
                explanation = explanation.ifBlank {
                    if (edits.isNotEmpty()) {
                        "使用 ${edits.size} 个局部编辑生成补丁。"
                    } else {
                        "未提供修改说明。"
                    }
                },
            ),
        )
        val approved = approvalRequester(request).get()
        if (!approved) return ToolExecutionResult("用户拒绝了这次文件修改。", success = false)
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "应用补丁",
                metadata = mapOf("file" to filePath),
            ),
        )
        applyFileContent(resolved, afterText)
        return ToolExecutionResult("已将修改应用到 $filePath")
    }

    private fun runCommand(
        command: String,
        workingDirectory: String?,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        return runCommandWithPolicy(command, workingDirectory, observer)
        if (command.isBlank()) return ToolExecutionResult("缺少 command 参数。", success = false)
        val directory = if (workingDirectory.isNullOrBlank()) {
            projectRoot
        } else {
            resolveProjectPath(workingDirectory)
                ?: return ToolExecutionResult("workingDirectory 超出了当前项目范围。", success = false)
        }

        val decision = CommandSafetySupport.evaluate(command)
        if (decision.policy == CommandPolicy.BLOCKED) {
            return ToolExecutionResult(decision.reason ?: "命令已被阻止。", success = false)
        }

        if (decision.policy == CommandPolicy.REQUIRE_APPROVAL) {
            observer.onUpdate(
                ToolProgressUpdate(
                    state = ToolInvocationState.PENDING,
                    title = "等待批准命令",
                    metadata = mapOf("command" to command, "workingDirectory" to directory.toString()),
                ),
            )
            val request = ApprovalRequest(
                id = "approval-${System.nanoTime()}",
                type = ApprovalRequest.Type.COMMAND,
                title = "执行命令于 ${projectRoot.relativize(directory).toString().ifBlank { "." }}",
                payload = ApprovalPayload.Command(command, directory.toString()),
            )
            val approved = approvalRequester(request).get()
            if (!approved) return ToolExecutionResult("用户拒绝了这次命令执行。", success = false)
        }

        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "执行命令",
                metadata = mapOf("command" to command, "workingDirectory" to directory.toString()),
            ),
        )
        val settings = shellSettings()
        val shell = settings.shellPath.ifBlank { com.idopen.idopen.settings.IDopenSettingsState.defaultShellPath() }
        val shellCommand = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf(shell, "-NoProfile", "-Command", command)
        } else {
            listOf(shell, "-lc", command)
        }

        val process = ProcessBuilder(shellCommand)
            .directory(directory.toFile())
            .redirectErrorStream(false)
            .start()

        val finished = process.waitFor(settings.commandTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ToolExecutionResult("命令执行超时，已超过 ${settings.commandTimeoutSeconds} 秒。", success = false)
        }

        val stdout = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        val summary = buildString {
            appendLine("退出码：${process.exitValue()}")
            if (stdout.isNotBlank()) {
                appendLine("标准输出：")
                appendLine(truncate(stdout, 8000))
            }
            if (stderr.isNotBlank()) {
                appendLine("错误输出：")
                appendLine(truncate(stderr, 8000))
            }
        }.trim()
        return ToolExecutionResult(summary, success = process.exitValue() == 0)
    }

    private fun applyPatchPreviewWithRecovery(
        filePath: String,
        newContent: String?,
        edits: List<PatchEdit>,
        explanation: String,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        validatePatchRequest(filePath)?.let { return it }
        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.PENDING,
                title = "等待批准补丁",
                metadata = mapOf("file" to filePath),
            ),
        )
        val resolved = requireNotNull(resolveProjectPath(filePath))
        val beforeText = if (Files.exists(resolved)) Files.readString(resolved, StandardCharsets.UTF_8) else ""
        val patchResolution = resolvePatchContent(filePath, beforeText, newContent, edits)
        if (patchResolution.error != null) {
            return ToolExecutionResult(
                content = patchResolution.error,
                success = false,
                recoveryHint = patchResolution.recoveryHint,
            )
        }

        val request = ApprovalRequest(
            id = "approval-${System.nanoTime()}",
            type = ApprovalRequest.Type.PATCH,
            title = "写入文件 $filePath",
            payload = ApprovalPayload.Patch(
                filePath = filePath,
                beforeText = beforeText,
                afterText = patchResolution.afterText.orEmpty(),
                explanation = explanation.ifBlank {
                    if (edits.isNotEmpty()) {
                        "Generated from ${edits.size} focused edits."
                    } else {
                        "Generated from a full file rewrite."
                    }
                },
            ),
        )
        val approved = approvalRequester(request).get()
        if (!approved) {
            return ToolExecutionResult(
                content = "用户拒绝了这次文件修改。",
                success = false,
                recoveryHint = FailureRecoverySupport.approvalHint(request),
            )
        }

        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "应用补丁",
                metadata = mapOf("file" to filePath),
            ),
        )
        applyFileContent(resolved, patchResolution.afterText.orEmpty())
        return ToolExecutionResult("已将修改应用到 $filePath")
    }

    private fun runCommandWithPolicy(
        command: String,
        workingDirectory: String?,
        observer: ToolExecutionObserver,
    ): ToolExecutionResult {
        validateCommandRequest(command, workingDirectory)?.let { return it }
        val directory = if (workingDirectory.isNullOrBlank()) {
            projectRoot
        } else {
            requireNotNull(resolveProjectPath(workingDirectory))
        }

        val decision = CommandSafetySupport.evaluate(command)
        if (decision.policy == CommandPolicy.BLOCKED) {
            return ToolExecutionResult(
                content = decision.reason ?: "命令已被阻止。",
                success = false,
                recoveryHint = "Use a safer read-only command or explain why a mutating command is necessary.",
            )
        }

        if (decision.policy == CommandPolicy.REQUIRE_APPROVAL) {
            observer.onUpdate(
                ToolProgressUpdate(
                    state = ToolInvocationState.PENDING,
                    title = "等待批准命令",
                    metadata = mapOf("command" to command, "workingDirectory" to directory.toString()),
                ),
            )
            val request = ApprovalRequest(
                id = "approval-${System.nanoTime()}",
                type = ApprovalRequest.Type.COMMAND,
                title = "执行命令于 ${projectRoot.relativize(directory).toString().ifBlank { "." }}",
                payload = ApprovalPayload.Command(command, directory.toString()),
            )
            val approved = approvalRequester(request).get()
            if (!approved) {
                return ToolExecutionResult(
                    content = "用户拒绝了这次命令执行。",
                    success = false,
                    recoveryHint = FailureRecoverySupport.approvalHint(request),
                )
            }
        }

        observer.onUpdate(
            ToolProgressUpdate(
                state = ToolInvocationState.RUNNING,
                title = "执行命令",
                metadata = mapOf("command" to command, "workingDirectory" to directory.toString()),
            ),
        )
        val settings = shellSettings()
        val shell = settings.shellPath.ifBlank { com.idopen.idopen.settings.IDopenSettingsState.defaultShellPath() }
        val shellCommand = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf(shell, "-NoProfile", "-Command", command)
        } else {
            listOf(shell, "-lc", command)
        }

        val process = ProcessBuilder(shellCommand)
            .directory(directory.toFile())
            .redirectErrorStream(false)
            .start()

        val finished = process.waitFor(settings.commandTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ToolExecutionResult(
                content = "命令执行超时，已超过 ${settings.commandTimeoutSeconds} 秒。",
                success = false,
                recoveryHint = "Try a narrower command, or inspect files directly with IDE tools before running another shell command.",
            )
        }

        val stdout = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        val summary = buildString {
            appendLine("退出码：${process.exitValue()}")
            if (stdout.isNotBlank()) {
                appendLine("标准输出：")
                appendLine(truncate(stdout, 8000))
            }
            if (stderr.isNotBlank()) {
                appendLine("错误输出：")
                appendLine(truncate(stderr, 8000))
            }
        }.trim()
        return ToolExecutionResult(
            content = summary,
            success = process.exitValue() == 0,
            recoveryHint = if (process.exitValue() == 0) {
                null
            } else {
                FailureRecoverySupport.toolHint("run_command", summary, command)
            },
        )
    }

    private fun validateReadRequest(path: String): ToolExecutionResult? {
        if (path.isBlank()) {
            return ToolExecutionResult(
                content = "缺少 path 参数。",
                success = false,
                recoveryHint = "Provide a project-relative path, or inspect the project tree first.",
            )
        }
        val resolved = resolveProjectPath(path) ?: return ToolExecutionResult(
            content = "路径超出了当前项目范围。",
            success = false,
            recoveryHint = "Use a path inside the current project root.",
        )
        if (!Files.exists(resolved)) {
            return ToolExecutionResult(
                content = "文件或目录不存在：$path",
                success = false,
                recoveryHint = "Check the project tree or search for the file name before retrying read_file.",
            )
        }
        if (!Files.isDirectory(resolved) && isLikelyBinary(resolved)) {
            return ToolExecutionResult(
                content = "无法读取二进制文件：$path",
                success = false,
                recoveryHint = "Read a text file instead, or inspect the containing directory.",
            )
        }
        return null
    }

    private fun validatePatchRequest(filePath: String): ToolExecutionResult? {
        if (filePath.isBlank()) {
            return ToolExecutionResult(
                content = "缺少 filePath 参数。",
                success = false,
                recoveryHint = "Provide a project-relative file path before generating a patch.",
            )
        }
        if (resolveProjectPath(filePath) == null) {
            return ToolExecutionResult(
                content = "路径超出了当前项目范围。",
                success = false,
                recoveryHint = "Only patch files inside the current project root.",
            )
        }
        return null
    }

    private fun validateCommandRequest(
        command: String,
        workingDirectory: String?,
    ): ToolExecutionResult? {
        if (command.isBlank()) {
            return ToolExecutionResult(
                content = "缺少 command 参数。",
                success = false,
                recoveryHint = "Provide a shell command, or prefer IDE read/search tools if you only need project inspection.",
            )
        }
        if (!workingDirectory.isNullOrBlank() && resolveProjectPath(workingDirectory) == null) {
            return ToolExecutionResult(
                content = "workingDirectory 超出了当前项目范围。",
                success = false,
                recoveryHint = "Keep command execution inside the current project root.",
            )
        }
        return null
    }

    private fun resolvePatchContent(
        filePath: String,
        beforeText: String,
        newContent: String?,
        edits: List<PatchEdit>,
    ): PatchPreviewResolution {
        return runCatching {
            when {
                !newContent.isNullOrBlank() -> PatchPreviewResolution(afterText = newContent)
                edits.isNotEmpty() -> PatchPreviewResolution(afterText = PatchEditSupport.apply(beforeText, edits))
                else -> error("必须提供 newContent 或 edits。")
            }
        }.getOrElse { throwable ->
            PatchPreviewResolution(
                error = "补丁生成失败：${throwable.message ?: "unknown error"}",
                recoveryHint = FailureRecoverySupport.patchHint(
                    filePath = filePath,
                    edits = edits,
                    message = throwable.message ?: "unknown error",
                ),
            )
        }
    }

    private fun applyFileContent(target: Path, newContent: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val localFs = LocalFileSystem.getInstance()
            val parentIo = target.parent.toFile()
            if (!parentIo.exists()) {
                parentIo.mkdirs()
            }
            val parentVf = localFs.refreshAndFindFileByIoFile(parentIo)
                ?: error("Unable to resolve parent directory for $target")
            val virtualFile = localFs.refreshAndFindFileByIoFile(target.toFile())
                ?: parentVf.createChildData(this, target.fileName.toString())
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            if (document != null) {
                document.setText(newContent)
                FileDocumentManager.getInstance().saveDocument(document)
            } else {
                VfsUtil.saveText(virtualFile, newContent)
            }
        }
    }

    private fun resolveProjectPath(candidate: String): Path? {
        val raw = Paths.get(candidate)
        val resolved = if (raw.isAbsolute) raw.normalize() else projectRoot.resolve(candidate).normalize()
        return if (resolved.startsWith(projectRoot)) resolved else null
    }

    private fun relativePath(file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val normalizedBase = Paths.get(basePath).toAbsolutePath().normalize()
        val normalizedFile = Paths.get(file.path).toAbsolutePath().normalize()
        return if (normalizedFile.startsWith(normalizedBase)) {
            normalizedBase.relativize(normalizedFile).toString().replace('\\', '/')
        } else {
            file.path
        }
    }

    private fun truncate(text: String, maxChars: Int = 12_000): String {
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n...[truncated]"
    }

    private fun isLikelyBinary(path: Path): Boolean {
        val extension = path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "jar", "class", "zip", "gz", "7z", "exe", "dll", "so", "bin", "pdf")) {
            return true
        }

        Files.newInputStream(path).use { stream ->
            val sample = ByteArray(4096)
            val read = stream.read(sample)
            if (read <= 0) return false
            var nonPrintable = 0
            for (index in 0 until read) {
                val value = sample[index].toInt() and 0xFF
                if (value == 0) return true
                if (value < 9 || (value in 14..31)) {
                    nonPrintable += 1
                }
            }
            return nonPrintable.toDouble() / read > 0.3
        }
    }

    private fun com.fasterxml.jackson.databind.JsonNode.optionalText(field: String): String? {
        val child = path(field)
        return if (child.isMissingNode || child.isNull) null else child.asText()
    }

    private fun com.fasterxml.jackson.databind.JsonNode.optionalInt(field: String): Int? {
        val child = path(field)
        return if (child.isMissingNode || child.isNull) null else child.asInt()
    }

    private fun parsePatchEdits(node: com.fasterxml.jackson.databind.JsonNode): List<PatchEdit> {
        return node.map { item ->
            PatchEdit(
                search = item.optionalText("search"),
                replace = item.optionalText("replace"),
                occurrence = item.optionalInt("occurrence"),
                replaceAll = item.path("replaceAll").takeIf { !it.isMissingNode && !it.isNull }?.asBoolean(false) ?: false,
                before = item.optionalText("before"),
                after = item.optionalText("after"),
                startLine = item.optionalInt("startLine"),
                endLine = item.optionalInt("endLine"),
                newText = item.optionalText("newText"),
            )
        }
    }

    private data class PatchPreviewResolution(
        val afterText: String? = null,
        val error: String? = null,
        val recoveryHint: String? = null,
    )
}
