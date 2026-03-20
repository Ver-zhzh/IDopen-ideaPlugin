package com.idopen.idopen.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

data class LoadedSkill(
    val name: String,
    val description: String,
    val location: Path,
    val baseDirectory: Path,
    val content: String,
)

object SkillSupport {
    private val globalSkillRoots = listOf(
        ".claude/skills",
        ".agents/skills",
        ".config/opencode/skill",
        ".config/opencode/skills",
    )

    private val projectSkillRoots = listOf(
        ".claude/skills",
        ".agents/skills",
        ".opencode/skill",
        ".opencode/skills",
    )

    fun available(projectRoot: Path): List<LoadedSkill> {
        val resolved = linkedMapOf<String, LoadedSkill>()
        discoveryRoots(projectRoot).forEach { root ->
            discoverSkillFiles(root).forEach { file ->
                parseSkillFile(projectRoot, file)?.let { skill ->
                    resolved[skill.name.lowercase()] = skill
                }
            }
        }
        return resolved.values.sortedBy { it.name.lowercase() }
    }

    fun get(projectRoot: Path, name: String): LoadedSkill? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null
        return available(projectRoot).firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    fun toolDescription(projectRoot: Path): String {
        val list = available(projectRoot)
        return if (list.isEmpty()) {
            "Load a specialized skill that provides domain-specific instructions and workflows. No skills are currently available."
        } else {
            listOf(
                "Load a specialized skill that provides domain-specific instructions and workflows.",
                "",
                "When a task matches one of the available skills below, call this tool to load the full skill instructions into the conversation.",
                "",
                "Tool output includes a <skill_content name=\"...\"> block with the loaded instructions and sampled bundled files.",
                "",
                format(list, verbose = false),
            ).joinToString("\n")
        }
    }

    fun systemPromptSection(projectRoot: Path): String? {
        val list = available(projectRoot)
        if (list.isEmpty()) return null
        return listOf(
            "Skills provide specialized instructions and workflows for specific tasks.",
            "Use the skill tool to load a skill when a task matches its description.",
            format(list, verbose = true),
        ).joinToString("\n")
    }

    fun loadToolResult(projectRoot: Path, requestedName: String): ToolExecutionResult {
        val name = requestedName.trim()
        if (name.isBlank()) {
            return ToolExecutionResult(
                content = "Missing skill name parameter.",
                success = false,
                recoveryHint = "Choose one of the exact available skill names before retrying.",
            )
        }
        val skill = get(projectRoot, name)
            ?: return ToolExecutionResult(
                content = "Skill \"$name\" not found. Available skills: ${available(projectRoot).joinToString(", ") { it.name }.ifBlank { "none" }}",
                success = false,
                recoveryHint = "Call skill with one of the exact available skill names from the tool description.",
            )

        val sampledFiles = sampleBundledFiles(projectRoot, skill.baseDirectory)
        val body = buildString {
            appendLine("<skill_content name=\"${xmlEscape(skill.name)}\">")
            appendLine("# Skill: ${skill.name}")
            appendLine()
            appendLine("Description: ${skill.description}")
            appendLine("Base directory: ${relativeToProject(projectRoot, skill.baseDirectory)}")
            appendLine()
            appendLine(skill.content.trim())
            appendLine()
            appendLine("<skill_files>")
            sampledFiles.forEach { appendLine("  <file>${xmlEscape(it)}</file>") }
            appendLine("</skill_files>")
            appendLine("</skill_content>")
        }
        return ToolExecutionResult(body.trim())
    }

    fun format(list: List<LoadedSkill>, verbose: Boolean): String {
        if (list.isEmpty()) return "No skills are currently available."
        return if (verbose) {
            buildString {
                appendLine("<available_skills>")
                list.forEach { skill ->
                    appendLine("  <skill>")
                    appendLine("    <name>${xmlEscape(skill.name)}</name>")
                    appendLine("    <description>${xmlEscape(skill.description)}</description>")
                    appendLine("    <location>${xmlEscape(skill.location.toString())}</location>")
                    appendLine("  </skill>")
                }
                append("</available_skills>")
            }
        } else {
            buildString {
                appendLine("## Available Skills")
                list.forEach { skill ->
                    appendLine("- **${skill.name}**: ${skill.description}")
                }
            }.trim()
        }
    }

    private fun discoveryRoots(projectRoot: Path): List<Path> {
        val globalRoots = currentUserHome()
            ?.let { home -> globalSkillRoots.map { home.resolve(it).normalize() } }
            .orEmpty()
        val projectRoots = projectSkillRoots.map { projectRoot.resolve(it).normalize() }
        return (globalRoots + projectRoots).filter { Files.isDirectory(it) }
    }

    private fun currentUserHome(): Path? {
        val home = System.getProperty("user.home")?.trim().orEmpty()
        if (home.isBlank()) return null
        return runCatching { Path.of(home).toAbsolutePath().normalize() }.getOrNull()
    }

    internal fun parseSkillFile(projectRoot: Path, path: Path): LoadedSkill? {
        if (!Files.isRegularFile(path) || !path.fileName.toString().equals("SKILL.md", ignoreCase = true)) {
            return null
        }
        val text = Files.readString(path)
        val (metadata, body) = extractFrontmatter(text)
        val content = body.trim()
        if (content.isBlank()) return null
        val name = metadata["name"]
            ?.takeIf { it.isNotBlank() }
            ?: path.parent?.fileName?.toString()
            ?: return null
        val description = metadata["description"]
            ?.takeIf { it.isNotBlank() }
            ?: fallbackDescription(content)
        return LoadedSkill(
            name = name.trim(),
            description = description.trim(),
            location = path.toAbsolutePath().normalize(),
            baseDirectory = path.parent?.toAbsolutePath()?.normalize() ?: projectRoot.toAbsolutePath().normalize(),
            content = content,
        )
    }

    private fun discoverSkillFiles(root: Path): List<Path> {
        val stream = Files.walk(root)
        return try {
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().equals("SKILL.md", ignoreCase = true) }
                .sorted(Comparator.naturalOrder())
                .iterator()
                .asSequence()
                .toList()
        } finally {
            stream.close()
        }
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
            ?.take(160)
            ?: "No description provided."
    }

    private fun sampleBundledFiles(projectRoot: Path, baseDirectory: Path): List<String> {
        val stream = Files.walk(baseDirectory)
        return try {
            stream
                .filter { path -> Files.isRegularFile(path) && !path.fileName.toString().equals("SKILL.md", ignoreCase = true) }
                .sorted(Comparator.naturalOrder())
                .iterator()
                .asSequence()
                .take(10)
                .map { relativeToProject(projectRoot, it) }
                .toList()
        } finally {
            stream.close()
        }
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

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
