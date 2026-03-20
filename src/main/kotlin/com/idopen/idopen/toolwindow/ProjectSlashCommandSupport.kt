package com.idopen.idopen.toolwindow

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal data class LoadedProjectSlashCommand(
    val name: String,
    val description: String,
    val argumentHint: String?,
    val agent: String?,
    val model: String?,
    val path: Path,
    val template: String,
)

internal object ProjectSlashCommandSupport {
    private val globalCommandRoots = listOf(
        ".config/opencode/command",
        ".config/opencode/commands",
    )

    private val projectCommandRoots = listOf(
        ".opencode/command",
        ".opencode/commands",
        ".claude/commands",
    )

    fun available(projectRoot: Path): List<LoadedProjectSlashCommand> {
        val resolved = linkedMapOf<String, LoadedProjectSlashCommand>()
        discoveryRoots(projectRoot).forEach { root ->
            discoverCommandFiles(root).forEach { file ->
                parseCommandFile(root, file)?.let { command ->
                    resolved[command.name.lowercase()] = command
                }
            }
        }
        return resolved.values.sortedBy { it.name.lowercase() }
    }

    fun find(projectRoot: Path, name: String): LoadedProjectSlashCommand? {
        val normalized = name.trim().lowercase()
        if (normalized.isBlank()) return null
        return available(projectRoot).firstOrNull { it.name.lowercase() == normalized }
    }

    fun format(projectRoot: Path, commands: List<LoadedProjectSlashCommand>, verbose: Boolean = false): String {
        if (commands.isEmpty()) return "No slash commands are currently available."
        return buildString {
            appendLine("## Commands")
            commands.forEach { command ->
                append("- /")
                append(command.name)
                append(": ")
                appendLine(command.description)
                if (verbose) {
                    command.argumentHint?.let { appendLine("  argument-hint: $it") }
                    command.agent?.let { appendLine("  agent: $it") }
                    command.model?.let { appendLine("  model: $it") }
                    appendLine("  path: ${relativeToProject(projectRoot, command.path)}")
                }
            }
        }.trim()
    }

    private fun discoveryRoots(projectRoot: Path): List<Path> {
        val globalRoots = currentUserHome()
            ?.let { home -> globalCommandRoots.map { home.resolve(it).normalize() } }
            .orEmpty()
        val projectRoots = projectCommandRoots.map { projectRoot.resolve(it).normalize() }
        return (globalRoots + projectRoots).filter { Files.isDirectory(it) }
    }

    private fun currentUserHome(): Path? {
        val home = System.getProperty("user.home")?.trim().orEmpty()
        if (home.isBlank()) return null
        return runCatching { Path.of(home).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun discoverCommandFiles(root: Path): List<Path> {
        val stream = Files.walk(root)
        return try {
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".md", ignoreCase = true) }
                .sorted(Comparator.naturalOrder())
                .iterator()
                .asSequence()
                .toList()
        } finally {
            stream.close()
        }
    }

    private fun parseCommandFile(root: Path, path: Path): LoadedProjectSlashCommand? {
        if (!Files.isRegularFile(path)) return null
        val text = Files.readString(path)
        val (metadata, body) = extractFrontmatter(text)
        val template = body.trim()
        if (template.isBlank()) return null
        val relativeName = root.relativize(path).toString().replace('\\', '/').removeSuffix(".md")
        if (relativeName.isBlank()) return null
        val description = metadata["description"]
            ?.takeIf { it.isNotBlank() }
            ?: fallbackDescription(template)
        return LoadedProjectSlashCommand(
            name = relativeName,
            description = description.trim(),
            argumentHint = metadata["argument-hint"]?.takeIf { it.isNotBlank() }?.trim(),
            agent = metadata["agent"]?.takeIf { it.isNotBlank() }?.trim(),
            model = metadata["model"]?.takeIf { it.isNotBlank() }?.trim(),
            path = path.toAbsolutePath().normalize(),
            template = template,
        )
    }

    private fun extractFrontmatter(text: String): Pair<Map<String, String>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, String>() to normalized
        val endIndex = normalized.indexOf("\n---\n", startIndex = 4)
        if (endIndex < 0) return emptyMap<String, String>() to normalized
        val header = normalized.substring(4, endIndex)
        val body = normalized.substring(endIndex + "\n---\n".length)
        val metadata = linkedMapOf<String, String>()
        header.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                if (key.isNotBlank() && value.isNotBlank()) {
                    metadata[key] = value
                }
            }
        return metadata to body
    }

    private fun fallbackDescription(content: String): String {
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.startsWith("```") &&
                    !line.startsWith("---")
            }
            ?.take(120)
            ?: "Command"
    }

    private fun relativeToProject(projectRoot: Path, value: Path): String {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedValue = value.toAbsolutePath().normalize()
        return if (normalizedValue.startsWith(normalizedRoot)) {
            normalizedRoot.relativize(normalizedValue).toString().replace('\\', '/')
        } else {
            normalizedValue.toString().replace('\\', '/')
        }
    }
}
