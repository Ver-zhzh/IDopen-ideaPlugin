package com.idopen.idopen.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

data class LoadedProjectAgent(
    val name: String,
    val description: String,
    val model: String?,
    val mode: SessionMode?,
    val path: Path,
    val prompt: String,
)

object ProjectAgentSupport {
    private val globalAgentRoots = listOf(
        ".config/opencode/agent",
        ".config/opencode/agents",
        ".idopen/agent",
        ".idopen/agents",
    )

    private val projectAgentRoots = listOf(
        ".claude/agents",
        ".agents",
        ".opencode/agent",
        ".opencode/agents",
        ".idopen/agent",
        ".idopen/agents",
    )

    fun available(projectRoot: Path): List<LoadedProjectAgent> {
        val resolved = linkedMapOf<String, LoadedProjectAgent>()
        discoveryRoots(projectRoot).forEach { root ->
            discoverAgentFiles(root).forEach { file ->
                parseAgentFile(root, file)?.let { agent ->
                    resolved[agent.name.lowercase()] = agent
                }
            }
        }
        return resolved.values.sortedBy { it.name.lowercase() }
    }

    fun find(projectRoot: Path, name: String): LoadedProjectAgent? {
        val normalized = name.trim().lowercase()
        if (normalized.isBlank()) return null
        return available(projectRoot).firstOrNull { it.name.lowercase() == normalized }
    }

    fun format(projectRoot: Path, agents: List<LoadedProjectAgent>, verbose: Boolean = false): String {
        if (agents.isEmpty()) return "No agents are currently available."
        return buildString {
            appendLine("## Agents")
            agents.forEach { agent ->
                append("- @")
                append(agent.name)
                append(": ")
                appendLine(agent.description)
                if (verbose) {
                    agent.model?.let { appendLine("  model: $it") }
                    agent.mode?.let { appendLine("  mode: ${it.name.lowercase()}") }
                    appendLine("  path: ${relativeToProject(projectRoot, agent.path)}")
                }
            }
        }.trim()
    }

    private fun discoveryRoots(projectRoot: Path): List<Path> {
        val globalRoots = currentUserHome()
            ?.let { home -> globalAgentRoots.map { home.resolve(it).normalize() } }
            .orEmpty()
        val projectRoots = projectAgentRoots.map { projectRoot.resolve(it).normalize() }
        return (globalRoots + projectRoots).filter { Files.isDirectory(it) }
    }

    private fun currentUserHome(): Path? {
        val home = System.getProperty("user.home")?.trim().orEmpty()
        if (home.isBlank()) return null
        return runCatching { Path.of(home).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun discoverAgentFiles(root: Path): List<Path> {
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

    private fun parseAgentFile(root: Path, path: Path): LoadedProjectAgent? {
        if (!Files.isRegularFile(path)) return null
        val text = Files.readString(path)
        val (metadata, body) = extractFrontmatter(text)
        val prompt = body.trim()
        if (prompt.isBlank()) return null
        val relativeName = root.relativize(path).toString().replace('\\', '/').removeSuffix(".md")
        if (relativeName.isBlank()) return null
        val description = metadata["description"]
            ?.takeIf { it.isNotBlank() }
            ?: fallbackDescription(prompt)
        return LoadedProjectAgent(
            name = relativeName,
            description = description.trim(),
            model = metadata["model"]?.takeIf { it.isNotBlank() }?.trim(),
            mode = metadata["mode"]?.let(SessionModeSupport::parse),
            path = path.toAbsolutePath().normalize(),
            prompt = prompt,
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
            ?: "Agent"
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
