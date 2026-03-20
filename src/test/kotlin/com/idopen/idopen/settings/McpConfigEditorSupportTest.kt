package com.idopen.idopen.settings

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpConfigEditorSupportTest {
    @Test
    fun `preferred user config falls back to legacy file when primary is missing`() {
        val userHome = Files.createTempDirectory("idopen-mcp-home")
        try {
            val legacyPath = userHome.resolve(".claude").resolve(".mcp.json")
            Files.createDirectories(legacyPath.parent)
            Files.writeString(legacyPath, """{"mcpServers":{}}""")

            val selected = McpConfigEditorSupport.preferredUserConfigPath(userHome)

            assertEquals(legacyPath.toAbsolutePath().normalize(), selected)
        } finally {
            deleteTree(userHome)
        }
    }

    @Test
    fun `preferred project config uses existing local claude file before creating a new primary file`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-project")
        try {
            val localPath = projectRoot.resolve(".claude").resolve(".mcp.json")
            Files.createDirectories(localPath.parent)
            Files.writeString(localPath, """{"mcpServers":{}}""")

            val selected = McpConfigEditorSupport.preferredProjectConfigPath(projectRoot)

            assertEquals(localPath.toAbsolutePath().normalize(), selected)
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `ensure config file creates an empty mcpServers template`() {
        val root = Files.createTempDirectory("idopen-mcp-config")
        try {
            val configPath = root.resolve(".mcp.json")

            McpConfigEditorSupport.ensureConfigFile(configPath)

            assertTrue(Files.isRegularFile(configPath))
            val content = Files.readString(configPath)
            assertTrue(content.contains("\"mcpServers\""))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `validate config text returns active and disabled server names`() {
        val validation = McpConfigEditorSupport.validateConfigText(
            """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "."]
                },
                "disabled-http": {
                  "url": "https://example.com/mcp",
                  "disabled": true
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(2, validation.totalServers)
        assertEquals(listOf("filesystem"), validation.activeServerNames)
        assertEquals(listOf("disabled-http"), validation.disabledServerNames)
    }

    @Test
    fun `save config pretty prints valid json`() {
        val root = Files.createTempDirectory("idopen-mcp-save")
        try {
            val configPath = root.resolve(".mcp.json")

            McpConfigEditorSupport.saveConfig(
                configPath,
                """{"mcpServers":{"demo":{"command":"node","args":["server.js"]}}}""",
            )

            val content = Files.readString(configPath)
            assertTrue(content.contains("\n"))
            assertTrue(content.contains("\"demo\""))
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: Path) {
        val stream = Files.walk(root)
        try {
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } finally {
            stream.close()
        }
    }
}
