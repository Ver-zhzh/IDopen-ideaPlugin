package com.idopen.idopen.settings

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpConfigEditorSupportTest {
    @Test
    fun `preferred user config uses idopen file when present`() {
        val userHome = Files.createTempDirectory("idopen-mcp-home")
        try {
            val preferredPath = userHome.resolve(".idopen").resolve("mcp.json")
            Files.createDirectories(preferredPath.parent)
            Files.writeString(preferredPath, """{"mcpServers":{}}""")

            val selected = McpConfigEditorSupport.preferredUserConfigPath(userHome)

            assertEquals(preferredPath.toAbsolutePath().normalize(), selected)
        } finally {
            deleteTree(userHome)
        }
    }

    @Test
    fun `preferred user config always targets idopen path`() {
        val userHome = Files.createTempDirectory("idopen-mcp-home")
        try {
            val legacyPath = userHome.resolve(".claude").resolve(".mcp.json")
            Files.createDirectories(legacyPath.parent)
            Files.writeString(legacyPath, """{"mcpServers":{}}""")

            val selected = McpConfigEditorSupport.preferredUserConfigPath(userHome)

            assertEquals(userHome.resolve(".idopen").resolve("mcp.json").toAbsolutePath().normalize(), selected)
        } finally {
            deleteTree(userHome)
        }
    }

    @Test
    fun `load user config imports legacy content into idopen target`() {
        val userHome = Files.createTempDirectory("idopen-mcp-home")
        try {
            val legacyPath = userHome.resolve(".claude.json")
            Files.writeString(legacyPath, """{"mcpServers":{"legacy":{"command":"node"}}}""")

            val document = McpConfigEditorSupport.loadUserConfig(userHome)

            assertEquals(userHome.resolve(".idopen").resolve("mcp.json").toAbsolutePath().normalize(), document.path)
            assertTrue(document.text.contains("\"legacy\""))
            assertTrue(!document.existed)
        } finally {
            deleteTree(userHome)
        }
    }

    @Test
    fun `preferred project config uses idopen file when present`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-project")
        try {
            val preferredPath = projectRoot.resolve(".idopen").resolve("mcp.json")
            Files.createDirectories(preferredPath.parent)
            Files.writeString(preferredPath, """{"mcpServers":{}}""")

            val selected = McpConfigEditorSupport.preferredProjectConfigPath(projectRoot)

            assertEquals(preferredPath.toAbsolutePath().normalize(), selected)
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `preferred project config falls back to legacy claude file when idopen primary is missing`() {
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
            val configPath = root.resolve(".idopen").resolve("mcp.json")

            McpConfigEditorSupport.ensureConfigFile(configPath)

            assertTrue(Files.isRegularFile(configPath))
            val content = Files.readString(configPath)
            assertTrue(content.contains("\"mcpServers\""))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `ensure user config file migrates legacy claude content into idopen path`() {
        val userHome = Files.createTempDirectory("idopen-mcp-config")
        try {
            val legacyPath = userHome.resolve(".claude.json")
            Files.writeString(legacyPath, """{"mcpServers":{"memory":{"command":"uvx"}}}""")

            McpConfigEditorSupport.ensureUserConfigFile(userHome)

            val preferredPath = userHome.resolve(".idopen").resolve("mcp.json")
            assertTrue(Files.isRegularFile(preferredPath))
            val content = Files.readString(preferredPath)
            assertTrue(content.contains("\"memory\""))
        } finally {
            deleteTree(userHome)
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
            val configPath = root.resolve(".idopen").resolve("mcp.json")

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

    @Test
    fun `format config text pretty prints valid json`() {
        val formatted = McpConfigEditorSupport.formatConfigText(
            """{"mcpServers":{"demo":{"command":"node","args":["server.js"]}}}""",
        )

        assertTrue(formatted.contains("\n"))
        assertTrue(formatted.contains("\"demo\""))
        assertTrue(formatted.endsWith(System.lineSeparator()))
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
