package com.idopen.idopen.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpSupportTest {
    @Test
    fun `available merges user project and local configs with later overrides`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-project")
        val userHome = Files.createTempDirectory("idopen-mcp-home")
        try {
            writeJson(
                userHome.resolve(".claude.json"),
                """
                {
                  "mcpServers": {
                    "memory": { "command": "npx", "args": ["-y", "@anthropic-ai/mcp-server-memory"] },
                    "context7": { "url": "https://docs.example.com/mcp" }
                  }
                }
                """.trimIndent(),
            )
            writeJson(
                projectRoot.resolve(".mcp.json"),
                """
                {
                  "mcpServers": {
                    "context7": { "disabled": true },
                    "sqlite": { "command": "uvx", "args": ["mcp-server-sqlite"] }
                  }
                }
                """.trimIndent(),
            )
            writeJson(
                projectRoot.resolve(".claude").resolve(".mcp.json"),
                """
                {
                  "mcpServers": {
                    "playwright": { "command": "npx", "args": ["@playwright/mcp@latest"], "env": { "DEBUG": "1" } }
                  }
                }
                """.trimIndent(),
            )

            val servers = McpSupport.available(projectRoot, userHome)

            assertEquals(listOf("memory", "playwright", "sqlite"), servers.map { it.name })
            assertEquals(McpScope.LOCAL, servers.first { it.name == "playwright" }.scope)
            assertEquals(listOf("@playwright/mcp@latest"), servers.first { it.name == "playwright" }.args)
            assertEquals("1", servers.first { it.name == "playwright" }.env["DEBUG"])
        } finally {
            deleteTree(projectRoot)
            deleteTree(userHome)
        }
    }

    @Test
    fun `describe tool result includes transport source and visible keys`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-describe")
        val userHome = Files.createTempDirectory("idopen-mcp-home-describe")
        try {
            writeJson(
                projectRoot.resolve(".mcp.json"),
                """
                {
                  "mcpServers": {
                    "remote-docs": {
                      "type": "http",
                      "url": "https://docs.example.com/mcp",
                      "headers": { "Authorization": "Bearer test" },
                      "oauth": { "scopes": ["docs.read", "docs.search"] }
                    }
                  }
                }
                """.trimIndent(),
            )

            val result = McpSupport.describeToolResult(projectRoot, "remote-docs")

            assertTrue(result.success)
            assertTrue(result.content.contains("<mcp_server name=\"remote-docs\">"))
            assertTrue(result.content.contains("Transport: http"))
            assertTrue(result.content.contains("Header keys: Authorization"))
            assertTrue(result.content.contains("OAuth scopes: docs.read, docs.search"))
            assertTrue(result.content.contains(".mcp.json"))
            assertEquals(listOf("remote-docs"), McpSupport.available(projectRoot, userHome).map { it.name })
        } finally {
            deleteTree(projectRoot)
            deleteTree(userHome)
        }
    }

    @Test
    fun `missing MCP server returns recoverable error`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-missing")
        try {
            val result = McpSupport.describeToolResult(projectRoot, "missing")

            assertFalse(result.success)
            assertNotNull(result.recoveryHint)
            assertTrue(result.content.contains("Available servers"))
        } finally {
            deleteTree(projectRoot)
        }
    }

    private fun writeJson(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
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
