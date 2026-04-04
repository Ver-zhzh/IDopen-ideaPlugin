package com.idopen.idopen.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.idopen.idopen.agent.McpSupport
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class McpConfigDocument(
    val path: Path,
    val text: String,
    val existed: Boolean,
)

data class McpConfigValidation(
    val totalServers: Int,
    val activeServerNames: List<String>,
    val disabledServerNames: List<String>,
) {
    fun summary(): String {
        val activePart = if (activeServerNames.isEmpty()) {
            "no active servers"
        } else {
            "active: ${activeServerNames.joinToString(", ")}"
        }
        val disabledPart = if (disabledServerNames.isEmpty()) {
            ""
        } else {
            " | disabled: ${disabledServerNames.joinToString(", ")}"
        }
        return "Valid MCP config with $totalServers server definition(s), $activePart$disabledPart"
    }
}

object McpConfigEditorSupport {
    private val mapper = ObjectMapper()

    fun emptyTemplate(): String {
        return prettyJson(
            mapOf(
                "mcpServers" to emptyMap<String, Any>(),
            ),
        )
    }

    fun stdioExampleTemplate(): String {
        return prettyJson(
            mapOf(
                "mcpServers" to mapOf(
                    "filesystem" to mapOf(
                        "command" to "npx",
                        "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "."),
                    ),
                ),
            ),
        )
    }

    fun httpExampleTemplate(): String {
        return prettyJson(
            mapOf(
                "mcpServers" to mapOf(
                    "remote-http" to mapOf(
                        "url" to "https://example.com/mcp",
                        "headers" to mapOf(
                            "Authorization" to "Bearer <token>",
                        ),
                    ),
                ),
            ),
        )
    }

    fun openUserConfig(project: Project, userHome: Path = defaultUserHome()): Path {
        val path = preferredUserConfigPath(userHome)
        ensureUserConfigFile(userHome)
        openConfig(project, path)
        return path
    }

    fun openProjectConfig(project: Project, projectRoot: Path): Path {
        val path = preferredProjectConfigPath(projectRoot)
        ensureConfigFile(path)
        openConfig(project, path)
        return path
    }

    fun openConfig(project: Project, path: Path): Path {
        ensureConfigFile(path)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: error("Unable to open MCP config: ${path.toString().replace('\\', '/')}")
        FileEditorManager.getInstance(project).openFile(file, true)
        return path
    }

    fun loadUserConfig(userHome: Path = defaultUserHome()): McpConfigDocument {
        val preferredPath = preferredUserConfigPath(userHome)
        if (Files.isRegularFile(preferredPath)) {
            return loadConfig(preferredPath)
        }
        val legacySource = legacyUserConfigPaths(userHome).firstOrNull { Files.isRegularFile(it) }
        return if (legacySource != null) {
            McpConfigDocument(
                path = preferredPath,
                text = Files.readString(legacySource),
                existed = false,
            )
        } else {
            loadConfig(preferredPath)
        }
    }

    fun loadProjectConfig(projectRoot: Path): McpConfigDocument {
        return loadConfig(preferredProjectConfigPath(projectRoot))
    }

    fun saveConfig(path: Path, text: String) {
        val normalized = prettyPrintValidatedConfig(text)
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, normalized)
    }

    fun formatConfigText(text: String): String {
        return prettyPrintValidatedConfig(text)
    }

    fun validateConfigText(text: String): McpConfigValidation {
        val root = mapper.readTree(text)
        require(root.isObject) { "The MCP config root must be a JSON object." }
        val serversNode = root.path("mcpServers")
        require(serversNode.isObject) { "A top-level mcpServers object is required." }

        val active = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        var total = 0
        val fields = serversNode.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            val name = entry.key.trim()
            require(name.isNotBlank()) { "MCP server names cannot be blank." }
            val serverNode = entry.value
            require(serverNode.isObject) { "Server \"$name\" must be a JSON object." }
            total += 1
            if (serverNode.path("disabled").asBoolean(false)) {
                disabled += name
                continue
            }
            val command = serverNode.path("command").asText("").trim()
            val url = serverNode.path("url").asText("").trim()
            require(command.isNotBlank() || url.isNotBlank()) {
                "Server \"$name\" must define either command or url."
            }
            active += name
        }
        return McpConfigValidation(
            totalServers = total,
            activeServerNames = active.sorted(),
            disabledServerNames = disabled.sorted(),
        )
    }

    fun statusSummary(projectRoot: Path, userHome: Path = defaultUserHome()): String {
        val servers = McpSupport.available(projectRoot, userHome)
        return if (servers.isEmpty()) {
            "No MCP servers detected yet. Edit the user or project MCP config to add one."
        } else {
            "Detected ${servers.size} MCP server(s): ${servers.joinToString(", ") { it.name }}"
        }
    }

    fun statusDetails(projectRoot: Path, userHome: Path = defaultUserHome()): String {
        val servers = McpSupport.available(projectRoot, userHome)
        val userPath = preferredUserConfigPath(userHome)
        val projectPath = preferredProjectConfigPath(projectRoot)
        return buildString {
            appendLine(statusSummary(projectRoot, userHome))
            appendLine("User config: ${userPath.toString().replace('\\', '/')}")
            appendLine("Project config: ${projectPath.toString().replace('\\', '/')}")
            if (servers.isNotEmpty()) {
                appendLine("Servers: ${servers.joinToString(", ") { "${it.name} [${it.scope.name.lowercase()}]" }}")
            }
        }.trim()
    }

    internal fun preferredUserConfigPath(userHome: Path): Path {
        val normalizedHome = userHome.toAbsolutePath().normalize()
        return normalizedHome.resolve(".idopen").resolve("mcp.json")
    }

    internal fun preferredProjectConfigPath(projectRoot: Path): Path {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val primary = normalizedRoot.resolve(".idopen").resolve("mcp.json")
        val secondary = normalizedRoot.resolve(".mcp.json")
        val legacy = normalizedRoot.resolve(".claude").resolve(".mcp.json")
        return when {
            Files.isRegularFile(primary) -> primary
            Files.isRegularFile(secondary) -> secondary
            Files.isRegularFile(legacy) -> legacy
            else -> primary
        }
    }

    internal fun ensureConfigFile(path: Path) {
        if (Files.isRegularFile(path)) return
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, emptyTemplate())
    }

    internal fun ensureUserConfigFile(userHome: Path) {
        val preferredPath = preferredUserConfigPath(userHome)
        if (Files.isRegularFile(preferredPath)) return
        val legacySource = legacyUserConfigPaths(userHome).firstOrNull { Files.isRegularFile(it) }
        preferredPath.parent?.let { Files.createDirectories(it) }
        val content = legacySource?.let { Files.readString(it) } ?: emptyTemplate()
        Files.writeString(preferredPath, content)
    }

    internal fun loadConfig(path: Path): McpConfigDocument {
        val normalized = path.toAbsolutePath().normalize()
        return if (Files.isRegularFile(normalized)) {
            McpConfigDocument(
                path = normalized,
                text = Files.readString(normalized),
                existed = true,
            )
        } else {
            McpConfigDocument(
                path = normalized,
                text = emptyTemplate(),
                existed = false,
            )
        }
    }

    private fun prettyPrintValidatedConfig(text: String): String {
        validateConfigText(text)
        val node = mapper.readTree(text)
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node) + System.lineSeparator()
    }

    private fun prettyJson(value: Any): String {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator()
    }

    private fun legacyUserConfigPaths(userHome: Path): List<Path> {
        val normalizedHome = userHome.toAbsolutePath().normalize()
        return listOf(
            normalizedHome.resolve(".idopen.json"),
            normalizedHome.resolve(".claude.json"),
            normalizedHome.resolve(".claude").resolve(".mcp.json"),
        )
    }

    private fun defaultUserHome(): Path {
        return Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize()
    }
}
