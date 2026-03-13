package com.idopen.idopen.agent

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
) {
    private val mapper = ObjectMapper()
    private val projectRoot = Paths.get(project.basePath ?: ".").toAbsolutePath().normalize()

    fun definitions(): List<ToolDefinition> = listOf(
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
            description = "Read a file from the current project.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("path"),
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "startLine" to mapOf("type" to "integer"),
                    "endLine" to mapOf("type" to "integer"),
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
            description = "Preview and apply a file change. newContent must contain the full updated file contents.",
            inputSchema = mapOf(
                "type" to "object",
                "required" to listOf("filePath", "newContent"),
                "properties" to mapOf(
                    "filePath" to mapOf("type" to "string"),
                    "newContent" to mapOf("type" to "string"),
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

    fun execute(call: ToolCall): ToolExecutionResult {
        val args = mapper.readTree(call.argumentsJson)
        return when (call.name) {
            "read_project_tree" -> readProjectTree(args.path("maxDepth").asInt(4), args.path("maxEntries").asInt(200))
            "read_file" -> readFile(
                path = args.path("path").asText(""),
                startLine = args.optionalInt("startLine"),
                endLine = args.optionalInt("endLine"),
            )
            "search_text" -> searchText(args.path("query").asText(""), args.path("maxResults").asInt(20))
            "get_current_file" -> getCurrentFile()
            "get_current_selection" -> getCurrentSelection()
            "apply_patch_preview" -> applyPatchPreview(
                filePath = args.path("filePath").asText(""),
                newContent = args.path("newContent").asText(""),
                explanation = args.path("explanation").asText(""),
            )
            "run_command" -> runCommand(
                command = args.path("command").asText(""),
                workingDirectory = args.optionalText("workingDirectory"),
            )
            else -> ToolExecutionResult("未知工具：${call.name}", success = false)
        }
    }

    private fun readProjectTree(maxDepth: Int, maxEntries: Int): ToolExecutionResult {
        val output = StringBuilder()
        var remaining = maxEntries.coerceAtLeast(1)
        ReadAction.run<RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                appendTree(root, root, maxDepth.coerceAtLeast(1), output, canAppend = {
                    remaining -= 1
                    remaining > 0
                })
            }
        }
        return ToolExecutionResult(if (output.isBlank()) "未找到项目文件。" else output.toString().trimEnd())
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
        output.append("  ".repeat(depth)).append(if (file.isDirectory) "[D] " else "[F] ").append(relative).append('\n')
        if (!file.isDirectory || depth == maxDepth) return
        file.children.sortedBy { it.name }.forEach { child ->
            appendTree(root, child, maxDepth, output, canAppend, depth + 1)
        }
    }

    private fun readFile(path: String, startLine: Int?, endLine: Int?): ToolExecutionResult {
        if (path.isBlank()) return ToolExecutionResult("缺少 path 参数。", success = false)
        val resolved = resolveProjectPath(path) ?: return ToolExecutionResult("路径超出了当前项目范围。", success = false)
        if (!Files.exists(resolved)) return ToolExecutionResult("文件不存在：$path", success = false)
        val lines = Files.readString(resolved, StandardCharsets.UTF_8).lines()
        val from = (startLine ?: 1).coerceAtLeast(1)
        val to = (endLine ?: lines.size).coerceAtMost(lines.size)
        val excerpt = lines.subList(from - 1, to).mapIndexed { index, line -> "${from + index}: $line" }.joinToString("\n")
        return ToolExecutionResult("文件：$path\n$excerpt".trim())
    }

    private fun searchText(query: String, maxResults: Int): ToolExecutionResult {
        if (query.isBlank()) return ToolExecutionResult("缺少 query 参数。", success = false)
        val results = mutableListOf<String>()
        ReadAction.run<RuntimeException> {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (results.size >= maxResults) return@iterateContent false
                if (file.isDirectory || file.fileType.isBinary || file.length > 512_000) return@iterateContent true
                val text = runCatching { VfsUtil.loadText(file) }.getOrNull() ?: return@iterateContent true
                text.lineSequence().forEachIndexed { index, line ->
                    if (results.size >= maxResults) return@forEachIndexed
                    if (line.contains(query, ignoreCase = true)) {
                        val relative = relativePath(file)
                        results += "$relative:${index + 1}: ${line.trim()}"
                    }
                }
                true
            }
        }
        return ToolExecutionResult(if (results.isEmpty()) "没有找到包含“$query”的内容。" else results.joinToString("\n"))
    }

    private fun getCurrentFile(): ToolExecutionResult {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return ToolExecutionResult("当前没有激活的编辑器。", success = false)
        val file = FileDocumentManager.getInstance().getFile(editor.document)
            ?: return ToolExecutionResult("当前没有激活的文件。", success = false)
        return ToolExecutionResult("文件：${relativePath(file)}\n${truncate(editor.document.text)}")
    }

    private fun getCurrentSelection(): ToolExecutionResult {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return ToolExecutionResult("当前没有激活的编辑器。", success = false)
        val selection = editor.selectionModel.selectedText
            ?: return ToolExecutionResult("当前编辑器没有选中文本。", success = false)
        return ToolExecutionResult(selection)
    }

    private fun applyPatchPreview(filePath: String, newContent: String, explanation: String): ToolExecutionResult {
        if (filePath.isBlank()) return ToolExecutionResult("缺少 filePath 参数。", success = false)
        val resolved = resolveProjectPath(filePath) ?: return ToolExecutionResult("路径超出了当前项目范围。", success = false)
        val beforeText = if (Files.exists(resolved)) Files.readString(resolved, StandardCharsets.UTF_8) else ""
        val request = ApprovalRequest(
            id = "approval-${System.nanoTime()}",
            type = ApprovalRequest.Type.PATCH,
            title = "写入文件 $filePath",
            payload = ApprovalPayload.Patch(filePath, beforeText, newContent, explanation.ifBlank { "未提供修改说明。" }),
        )
        val approved = approvalRequester(request).get()
        if (!approved) return ToolExecutionResult("用户拒绝了这次文件修改。", success = false)
        applyFileContent(resolved, newContent)
        return ToolExecutionResult("已将修改应用到 $filePath")
    }

    private fun runCommand(command: String, workingDirectory: String?): ToolExecutionResult {
        if (command.isBlank()) return ToolExecutionResult("缺少 command 参数。", success = false)
        val directory = if (workingDirectory.isNullOrBlank()) projectRoot else {
            resolveProjectPath(workingDirectory) ?: return ToolExecutionResult("workingDirectory 超出了当前项目范围。", success = false)
        }
        val request = ApprovalRequest(
            id = "approval-${System.nanoTime()}",
            type = ApprovalRequest.Type.COMMAND,
            title = "执行命令于 ${projectRoot.relativize(directory).toString().ifBlank { "." }}",
            payload = ApprovalPayload.Command(command, directory.toString()),
        )
        val approved = approvalRequester(request).get()
        if (!approved) return ToolExecutionResult("用户拒绝了这次命令执行。", success = false)

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

    private fun com.fasterxml.jackson.databind.JsonNode.optionalText(field: String): String? {
        val child = path(field)
        return if (child.isMissingNode || child.isNull) null else child.asText()
    }

    private fun com.fasterxml.jackson.databind.JsonNode.optionalInt(field: String): Int? {
        val child = path(field)
        return if (child.isMissingNode || child.isNull) null else child.asInt()
    }
}
