package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class McpInspectorSupportTest {
    private val mapper = ObjectMapper()

    @Test
    fun `inspect server includes tools resources templates and prompts`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-inspector")
        try {
            Files.writeString(
                projectRoot.resolve(".mcp.json"),
                """
                {
                  "mcpServers": {
                    "docs": {
                      "command": "node",
                      "args": ["server.js"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport { _, _ ->
                object : McpSession {
                    override fun listTools(): List<McpRemoteTool> = listOf(
                        McpRemoteTool("fetch_docs", "Fetch docs", mapper.createObjectNode().put("type", "object")),
                    )

                    override fun callTool(name: String, arguments: com.fasterxml.jackson.databind.JsonNode): McpCallResult {
                        return McpCallResult("ok", isError = false)
                    }

                    override fun listResources(): List<McpRemoteResource> = listOf(
                        McpRemoteResource("resource://docs/readme", "README", null, "text/markdown", null),
                    )

                    override fun readResource(uri: String): List<McpResourceContent> = emptyList()

                    override fun listResourceTemplates(): List<McpRemoteResourceTemplate> = listOf(
                        McpRemoteResourceTemplate("resource://docs/{slug}", "Doc page", null, "text/markdown"),
                    )

                    override fun listPrompts(): List<McpRemotePrompt> = listOf(
                        McpRemotePrompt("review", "Review docs", emptyList()),
                    )

                    override fun getPrompt(name: String, arguments: com.fasterxml.jackson.databind.JsonNode): McpPromptResult {
                        return McpPromptResult(null, emptyList())
                    }

                    override fun close() = Unit
                }
            }

            val result = McpInspectorSupport.inspectServer(projectRoot, "docs", runtime)

            assertTrue(result.contains("fetch_docs"))
            assertTrue(result.contains("resource://docs/readme"))
            assertTrue(result.contains("resource://docs/{slug}"))
            assertTrue(result.contains("review"))
        } finally {
            Files.walk(projectRoot).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `inspect server shows inspect only note for unsupported transports`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-inspector-legacy")
        try {
            Files.writeString(
                projectRoot.resolve(".mcp.json"),
                """
                {
                  "mcpServers": {
                    "legacy": {
                      "type": "sse",
                      "url": "https://example.com/sse"
                    }
                  }
                }
                """.trimIndent(),
            )

            val result = McpInspectorSupport.inspectServer(projectRoot, "legacy")

            assertTrue(result.contains("inspect-only"))
            assertTrue(result.contains("streamable HTTP"))
        } finally {
            Files.walk(projectRoot).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
